# Лаба 4 — отчёт по бенчмаркам

Реализация: [`hashmap.ConcurrentHashMap`](src/main/kotlin/hashmap/ConcurrentHashMap.kt) — striped-сегменты с `ReentrantLock`, separate chaining, lock-free чтения на горячем пути (`AtomicReferenceArray` + `@Volatile`).

Сравнивается с `java.util.concurrent.ConcurrentHashMap` (JDK), `Collections.synchronizedMap(HashMap)` (SYNC) и `java.util.HashMap` (однопоточный baseline, в коде называется `PlainHashMap`, в JSON — `UNSAFE`).

Развёрнутая версия с полным разбором: [`DRAFT_REPORT.md`](DRAFT_REPORT.md). Все числа в этом отчёте взяты из [`results/full/jmh-results.json`](results/full/jmh-results.json) (118 строк).

## Стенд

| Поле | Значение |
| --- | --- |
| CPU | AMD Ryzen 7 7800X3D (8 ядер / 16 потоков, 96 МБ L3) |
| JDK / JMH | OpenJDK 26.0.1 / JMH 1.37 |
| Heap | `-Xmx24g`, G1GC |
| Forks | 2; 5×10 с прогрев + 5×10 с измерение (Scaling: 5×1 с + 5×2 с) |

## Главные выводы

- **Чтения OWN скейлятся почти линейно до 8 потоков** (×7.9), затем плато под SMT.
- **Записи OWN не скейлятся за 8 потоков** (плато ≈ 35 M ops/s): 16 сегментных локов — узкое место. JDK CHM на bucket-уровне CAS-ит, доходит до ×7.4.
- **SYNC коллапсирует** уже после 1 потока (1 глобальный лок).
- **Латентность** медианно ≈ 50 нс у всех трёх concurrent-реализаций; разница в хвосте — это GC/safepoint-паузы, не структура данных.

## Чтения — `ReadBenchmark`

Aggregate Mops/s по числу потоков; ключи равномерно случайные из 1 048 576.

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 91.7 | 82.5 | 76.6 |
| 2 | 189.7 | 179.7 | 45.2 |
| 4 | 349.2 | 346.5 | 14.7 |
| 8 | **723.4** | **839.4** | 13.7 |
| 16 | 693.3 | 741.3 | 13.2 |
| 32 | 709.8 | 817.6 | 13.4 |

![Throughput чтений по числу потоков](results/full/graphs/throughput_threads_ReadBenchmark.png)

OWN и JDK идут вровень и почти линейно скейлятся до 8 ядер (8 потоков ≈ 8× от 1 потока), затем выходят на плато под SMT. SYNC обваливается из-за глобального лока.

## Записи — `WriteBenchmark`

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 15.9 | 15.1 | 15.5 |
| 2 | 23.2 | 30.4 | 19.4 |
| 4 | 32.0 | 57.1 | 10.7 |
| 8 | **36.2** | **111.2** | 9.7 |
| 16 | 29.8 | 107.8 | 9.5 |
| 32 | 31.7 | 101.9 | 9.6 |

![Throughput записей по числу потоков](results/full/graphs/throughput_threads_WriteBenchmark.png)

Главный результат: **OWN масштабируется только в ×2.3** до 8 потоков и проседает на 18 % на 16, тогда как JDK даёт **×7.4**. Причина — 16 сегментных `ReentrantLock`-ов в OWN: при 8 потоках вероятность коллизии по парадоксу дней рождений ≈ 88 %. JDK с JDK 8 использует CAS / synchronized на уровне бакетов (≈ 2 M бакетов при заполнении 1 M записей), коллизии почти не случаются.

Flame-графы для `write_thr16` подтверждают разницу: [`results/flame/write_thr16_OWN.html`](results/flame/write_thr16_OWN.html) (горячие фреймы — `ReentrantLock.lock/unlock`), [`results/flame/write_thr16_JDK.html`](results/flame/write_thr16_JDK.html) (CAS-retry).

## Смешанная нагрузка — `MixedBenchmark` @ `rs = 0.8`

При доле чтений 0.8 (read-heavy):

| threads | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 | 20.6 | 19.1 | 15.8 |
| 2 | 38.2 | 37.9 | 19.8 |
| 4 | 70.1 | 72.6 | 11.2 |
| 8 | **112.2** | **137.4** | 9.2 |
| 16 | 92.9 | 137.0 | 9.3 |
| 32 | 90.2 | 139.8 | 9.8 |

![Mixed throughput rs=0.8](results/full/graphs/throughput_threads_MixedBenchmark_rs0.8.png)

OWN пик на 8 потоках, потом регрессия −17 % на 16 t из-за того же лока на запись. JDK выходит на плато. Полные таблицы для `rs = 0.2` и `rs = 0.5` — в [`DRAFT_REPORT.md`](DRAFT_REPORT.md#смешанная-нагрузка--mixedbenchmark-range--1-048-576).

## Speedup относительно SYNC

![Speedup vs SYNC](results/full/graphs/speedup_vs_sync_by_family.png)

| семейство @ 8 t | OWN | JDK |
| --- | ---: | ---: |
| Read | ≈ 53× | ≈ 61× |
| Write | ≈ 3.7× | ≈ 11.5× |
| Mixed rs=0.8 | ≈ 12× | ≈ 15× |

## Масштабирование по числу записей — `ScalingBenchmark` @ 8 потоков

| записей | OWN | JDK | SYNC |
| ---: | ---: | ---: | ---: |
| 1 000 | 1 048 | 1 238 | 14.4 |
| 10 000 | **1 132** | 1 232 | 13.8 |
| 100 000 | 756 | 909 | 13.6 |
| 1 000 000 | 636 | 812 | 12.8 |
| 10 000 000 | 206 | 221 | 11.1 |
| 100 000 000 | 145 | 139 | 9.7 |
| 300 000 000 | 116 | 108 | 9.2 |

![Scaling по числу записей (log-log)](results/full/graphs/scaling_loglog.png)

Чётко виден переход кэш → DRAM на интервале 1 M → 10 M записей (working-set уходит за 96 МБ L3). При 300 M записей мап весит ≈ 24 ГБ и упирается в `-Xmx24g`, замеры там идут под GC.

## Латентность чтений — `ReadLatencyBenchmark.getSample`

Sample-mode, случайный ключ на каждый замер. Времена в нс.

| impl | mean | p50 | p99 | p99.9 | p99.99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| OWN | 52.9 | 50 | 120 | 410 | 2 812 |
| JDK | 49.2 | 40 | 120 | 390 | 2 828 |
| SYNC | 55.5 | 50 | 130 | 420 | 2 708 |

![Перцентили латентности](results/full/graphs/latency_percentiles.png)

Все три реализации сходятся к ≈ 50 нс на медиане. Хвост p99.99 ≈ 2.7–2.8 мкс одинаковый — это G1 паузы / safepoints, общие для JVM.

## jcstress

Quick mode (`jcstress { mode = "quick" }`). Все четыре теста проходят:

| Тест | Что проверяет |
| --- | --- |
| `PutGetStressTest` | `put` → concurrent `get` возвращает 0 или 1, не stale значение |
| `ConcurrentPutStressTest` | два concurrent `put`-а оставляют 10 или 20, без потерь |
| `MergeAtomicityStressTest` | два concurrent `merge(+1)` дают 2, никогда не 1 |
| `ResizeStressTest` | concurrent `put` + `get` сквозь resize видит только старый или только новый bucket-массив (`@Volatile var buckets`) |

## Воспроизведение

```bash
./gradlew test --no-daemon -q                            # юнит-тесты
./gradlew jcstress --no-daemon                           # concurrency stress
./gradlew jmh --no-daemon -Pjmh.heap=24g                 # полная матрица 118 строк
python3 scripts/plot_results.py                          # графики → results/full/graphs/
./meta_run_flame.sh                                      # flame для write_thr16 OWN/JDK
```

Подробные комментарии, ячейки с высокой дисперсией, heatmap-ы по `rs`, baseline `HashMap` и cache-hierarchy разбор — в [`DRAFT_REPORT.md`](DRAFT_REPORT.md).
