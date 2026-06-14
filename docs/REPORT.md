# Краткий отчёт по бенчмаркам ConcurrentHashMap — прогон `full`

> Краткая версия. Все таблицы, графики и подробный разбор — `DRAFT.md`.
> Реализации, метрики и чтение графиков — `METHODOLOGY.md`.

> Числа из [`results/full/jmh-results.json`](../results/full/jmh-results.json)
> (118 строк). Графики — `docs/img/full/`.

> Прогон снят на реализации с **динамическим fair-resize числа сегментов**
> (16 → 64 на 1 M записей). Контролируемое OFF/ON-сравнение, изолирующее
> вклад resize на одной машине, — **§5a**.

## 1. Условия эксперимента

- **Реализация:** [`hashmap.ConcurrentHashMap`](../src/main/kotlin/hashmap/ConcurrentHashMap.kt) —
  striped-сегменты (старт 16) с `ReentrantLock`, separate chaining,
  lock-free чтения (`AtomicReferenceArray` + `@Volatile`); число сегментов
  **растёт динамически** под нагрузкой (§5a).
- **Сравнение с:** JDK `ConcurrentHashMap`, `Collections.synchronizedMap`
  (SYNC), `java.util.HashMap` (baseline `PlainHashMap`, в JSON — `UNSAFE`).
- **Хост:** AMD Ryzen 7 7800X3D (8 ядер / 16 потоков, 96 МБ L3), Linux,
  OpenJDK 21.0.11, JMH 1.37, `-Xmx24g`, G1GC.
- **Профиль:** forks = 2; main 5×10 с прогрев + 5×10 с измерение;
  Scaling 5×1 с + 5×2 с; Latency sample-mode 5×500 мс + 5×1 с.

## 2. Главные выводы

- **Чтения OWN скейлятся почти линейно до 8 потоков** (×8.6), затем плато
  под SMT; OWN 512 против JDK 768 M ops/s на 8 потоках.
- **Записи OWN скейлятся ×4.5 до 66 M ops/s** на 8 потоках: динамический
  рост числа сегментов (16 → 64) снял потолок 16 локов. JDK всё ещё впереди
  за счёт bin-level CAS → ×7.6 (108 M).
- **SYNC коллапсирует** уже после 1 потока (один глобальный лок).
- **Латентность** медианно 50 нс у JDK/SYNC и 60 нс у OWN (mean 68/54/52 нс);
  разница в хвосте — GC/safepoint-паузы, не структура данных.

## 3. Чтения — `ReadBenchmark`

Aggregate Mops/s, range = 1 048 576.

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 59.7 | 84.1 | 66.4 |
| 2 | 115.9 | 170.7 | 43.1 |
| 4 | 280.3 | 375.9 | 20.0 |
| 8 | **512.2** | **768.0** | 16.0 |
| 16 | 529.1 | 746.3 | 16.4 |
| 32 | 434.4 | 778.4 | 16.3 |

![Throughput чтений по числу потоков](img/full/throughput_threads_ReadBenchmark.png)

OWN и JDK почти линейны до 8 ядер, затем плато под SMT. SYNC обваливается
из-за глобального лока.

## 4. Записи — `WriteBenchmark`

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 14.6 | 14.3 | 14.0 |
| 2 | 25.2 | 29.8 | 15.0 |
| 4 | 43.4 | 58.0 | 11.1 |
| 8 | **66.4** | **108.2** | 9.8 |
| 16 | 58.8 | 109.3 | 10.1 |
| 32 | 57.1 | 110.2 | 10.4 |

![Throughput записей по числу потоков](img/full/throughput_threads_WriteBenchmark.png)

OWN масштабируется в ×4.5 до 8 потоков: динамический рост раскладки
**16 → 64 сегмента** на 1 M записей размазывает 8 потоков по 64 локам (а не
16), и старый потолок 16 `ReentrantLock`-ов снят. На 16 потоках — мягкая
просадка −11 % (SMT-давление на стрипах). JDK по-прежнему впереди: CAS на
уровне бакетов (≈ 2 M бинов на 1 M записей), коллизии редки → ×7.6.
Flame-графы показывают структурную разницу:
[`write_thr16_OWN`](../results/flame/write_thr16_OWN.html) горит на
`ReentrantLock`, [`write_thr16_JDK`](../results/flame/write_thr16_JDK.html)
— на CAS-retry. Изоляция вклада resize (OFF vs ON) — **§5a**.

| OWN — `ReentrantLock` contention | JDK — CAS retry |
|:---:|:---:|
| ![Flamegraph OWN 16 threads](img/full/flame_write_thr16_OWN.png) | ![Flamegraph JDK 16 threads](img/full/flame_write_thr16_JDK.png) |


## 5. Смешанная нагрузка — `MixedBenchmark` @ `rs = 0.8`

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 14.0 | 16.9 | 13.5 |
| 2 | 28.9 | 34.2 | 18.7 |
| 4 | 57.0 | 68.8 | 10.9 |
| 8 | **102.9** | **130.0** | 9.7 |
| 16 | 103.6 | 130.0 | 8.9 |
| 32 | 95.0 | 132.1 | 8.0 |

![Mixed throughput rs=0.8](img/full/throughput_threads_MixedBenchmark_rs0.8.png)

OWN выходит на плато ~103 M на 8–16 потоках (на `rs = 0.8` регрессии 16 t
нет); мягкая просадка −12 % появляется при меньшей доле чтений
(`rs = 0.2 / 0.5`, см. `DRAFT.md` §5). JDK на плато ~130 M.

## 6. Speedup относительно SYNC

![Speedup vs SYNC](img/full/speedup_vs_sync_by_family.png)

| семейство @ 8 t | OWN | JDK |
| --- | ---: | ---: |
| Read | ≈ 32× | ≈ 48× |
| Write | ≈ 6.8× | ≈ 11× |
| Mixed rs=0.8 | ≈ 11× | ≈ 13× |

## 7. Масштабирование по числу записей — `ScalingBenchmark` @ 8 потоков

| записей | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 000 | 981 | 1 186 | 13.1 |
| 10 000 | **1 020** | 1 208 | 17.4 |
| 100 000 | 732 | 901 | 12.7 |
| 1 000 000 | 511 | 699 | 9.4 |
| 10 000 000 | 133 | 217 | 5.8 |
| 100 000 000 | 85 | 112 | 4.2 |

![Scaling по числу записей (log-log)](img/full/scaling_loglog.png)

Виден переход кэш → DRAM на 1 M → 10 M записей (working-set уходит за
96 МБ L3).

## 8. Латентность чтений — `ReadLatencyBenchmark.getSample`

Sample-mode, случайный ключ на каждый замер. Времена в нс.

| impl | mean | p50 | p99 | p99.9 | p99.99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| OWN | 68.4 | 60 | 180 | 550 | 3 395 |
| JDK | 54.3 | 50 | 130 | 460 | 3 068 |
| SYNC | 51.7 | 50 | 120 | 450 | 3 088 |

![Перцентили латентности](img/full/latency_percentiles.png)

JDK/SYNC медианно ≈ 50 нс, OWN чуть выше (p50 60 нс, mean 68 нс) — resize-
раскладка добавляет немного pointer-chasing. Хвост p99.99 ≈ 3.1–3.4 мкс
у всех — это G1-паузы / safepoints, общие для JVM.

## 9. Аномалии и data quality

| Severity | Аномалия | Доказательство |
| --- | --- | --- |
| TIER-2 | OWN write −11 % на 16 t (SMT) | `66.4 → 58.8 M`; 64 стрипа под SMT-давлением |
| TIER-2 | OWN mixed регрессия −12 % на 16 t (rs0.2/0.5) | `rs0.2 −12 %, rs0.5 −12 %, rs0.8 ≈0` |
| TIER-2 | `readOwnSingle_thr01` < `read_thr01` OWN | `53.4 < 59.7 M` — dual-фикстура `*Unsafe` |
| TIER-3 | `read_thr01/02` JDK широкий CI | `relErr 25 % / 41 %` — JIT-бимодальность, `fork ≥ 5` |
| TIER-3 | scaling OWN @ 1 M / 300 M | `relErr 18 % / 101 %` — DRAM + GC |

Полный разбор с tiers — `DRAFT.md` §10.

## 10. jcstress

Quick-mode, все четыре теста проходят:

| Тест | Что проверяет |
| --- | --- |
| `PutGetStressTest` | `put` → concurrent `get` = 0 или 1, не stale |
| `ConcurrentPutStressTest` | два `put` → 10 или 20, без потерь |
| `MergeAtomicityStressTest` | два `merge(+1)` → 2, никогда 1 |
| `ResizeStressTest` | `put`+`get` сквозь resize видят целый bucket-массив |

## 11. Заключение

- **Read-heavy:** OWN скейлится как JDK (lock-free чтения), но в абсолюте
  отстаёт ~30 % (512 против 768 M @ 8 t) — resize-раскладка чуть дороже на чтении.
- **Write-heavy:** динамический рост числа сегментов поднял OWN до ×4.5
  (66 M @ 8 t) — потолок 16 локов снят (изоляция вклада: +67 % OFF→ON, §5a);
  максимум под запись по-прежнему за JDK (108 M, bin-level CAS).
- **SYNC** — не использовать под конкуренцией.
- **Корректность** OWN (включая concurrent resize) подтверждена `jcstress`
  + JUnit.

## Воспроизведение

```bash
./gradlew test --no-daemon -q                   # JUnit
./gradlew jcstress --no-daemon                  # concurrency stress (quick)
./gradlew jmh --no-daemon -Pjmh.heap=24g        # полная матрица 118 строк
python3 scripts/plot_results.py                 # графики → results/full/graphs/
```
