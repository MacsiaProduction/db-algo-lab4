# Методология бенчмарка ConcurrentHashMap

> Справочный документ к `DRAFT.md` и `REPORT.md`. Описывает **что
> измеряется**, **как именно**, и **как читать каждый график**. Не
> зависит от конкретного прогона: после ре-рана числа в отчётах
> обновятся, но методика и интерпретация графиков останутся теми же.

Содержание:
1. [Цель и метод](#1-цель-и-метод)
2. [Реализации под тестом](#2-реализации-под-тестом)
3. [Метрики (что лежит в каждом поле JSON)](#3-метрики)
4. [Методология замеров](#4-методология-замеров)
5. [Матрица бенчмарков](#5-матрица-бенчмарков)
6. [Как читать каждый график](#6-как-читать-каждый-график)
7. [Аномалии и data quality](#7-аномалии-и-data-quality)
8. [jcstress: проверка корректности](#8-jcstress-проверка-корректности)
9. [Воспроизведение](#9-воспроизведение)

---

## 1. Цель и метод

**Лаб задача:** реализовать потокобезопасную хеш-таблицу с закрытой
адресацией (separate chaining) и сравнить её с эталонами из JDK по
пропускной способности, латентности и масштабированию под нагрузкой.

**Метод:** через JMH снимаем **пропускную способность** (`ops/s`) на
read-, write- и mixed-нагрузках при росте числа потоков (1 → 32),
**латентность** lookup-а (sample-mode, перцентили) и **масштабирование**
read-нагрузки по числу записей в мапе (1 k → 300 M). Каждая нагрузка
прогоняется на трёх (для масштабирования — на трёх, для baseline — на
четырёх) реализациях при **одинаковых** параметрах, после чего:
- строится throughput-vs-threads по каждому семейству,
- считается speedup относительно `SYNC` baseline,
- отдельным прогоном фиксируется per-entry-латентность с хвостами,
- логируется список численно подтверждённых аномалий.

Корректность (а не производительность) проверяется отдельно — `jcstress`
(§8) и JUnit-тесты (`src/test/kotlin/hashmap/`).

---

## 2. Реализации под тестом

Все четыре скрыты за интерфейсом
[`IntLongMap`](../src/jmh/kotlin/benchmarks/MapSupport.kt) (`Int`-ключи,
`Long`-значения), чтобы бенчмарк-код был общим.

### 2.1. OWN — `hashmap.ConcurrentHashMap`

Собственная реализация
([`ConcurrentHashMap.kt`](../src/main/kotlin/hashmap/ConcurrentHashMap.kt)):
**`segmentCount = 16` striped-сегментов**, в каждом `ReentrantLock` +
отдельный bucket-массив (`AtomicReferenceArray`), separate chaining.

- **Запись** (`put` / `merge`): берёт лок своего сегмента
  (`spread(hash) and segmentMask`), затем обходит цепочку под локом.
- **Чтение** (`get`): **без лока** — читает голову бакета через
  `AtomicReferenceArray.get` (volatile-семантика) и идёт по
  `@Volatile Node.next`. Видимость свежих записей и swap'а массива при
  resize обеспечивает `@Volatile var buckets`.
- **Resize**: per-segment, под локом сегмента; читатели видят либо
  старый, либо новый массив целиком.

**Узкое место по записи:** при ≤ 16 сегментах число параллельных
писателей упирается в число сегментов. При 8 потоках вероятность
коллизии по сегменту (парадокс дней рождений) уже ≈ 88 %.

### 2.2. JDK — `java.util.concurrent.ConcurrentHashMap`

Эталон из JDK 8+. Лочит/CAS-ит **на уровне отдельного bucket-bin**, без
низкого сегментного потолка: при ~2 M бинов на 1 M записей два потока
почти никогда не конкурируют за один bin.

### 2.3. SYNC — `Collections.synchronizedMap(HashMap)`

Один глобальный лок на весь мап. Любая операция (даже чтение)
сериализуется — baseline «как делать не надо» под конкуренцией.

### 2.4. PlainHashMap (в JSON / enum — `UNSAFE`)

Тонкая обёртка над `java.util.HashMap`
([`PlainHashMap.kt`](../src/main/kotlin/hashmap/PlainHashMap.kt)),
**однопоточная**. Нужна как нижняя граница стоимости операции без всякой
синхронизации. **Название `UNSAFE` историческое и не имеет отношения к
`sun.misc.Unsafe`** — на графиках ярлык `HashMap`.

---

## 3. Метрики

Каждая строка в `results/full/jmh-results.json` — одна точка
(benchmark × params × fork-aggregate). Поля:

| Поле | Что значит | Как меряется |
|---|---|---|
| `benchmark` | полное имя `<class>.<method>` | строка |
| `params` | `impl`, `rangeStr`, `readShareStr`, `entriesStr` | конфигурация точки |
| `mode` | `thrpt` / `sample` | режим JMH |
| `threads` | число рабочих потоков | из `@Threads` |
| `primaryMetric.score` | основная метрика | throughput: агрегат `ops/s`; latency: среднее `ns/op` |
| `primaryMetric.scoreError` | **99.9 % CI half-width** на score | разброс между форками + итерациями |
| `primaryMetric.scorePercentiles` | p50…p100 (только sample-mode) | распределение per-op латентности |
| `forks`, `warmupIterations`, `measurementIterations` | бюджет замера | из `jmh { }` (см. §4) |

**О throughput-семантике.** При `threads > 1` JMH выдаёт
**агрегированный `ops/s` по всем рабочим потокам**, а не на поток.
Реализации сравниваются строго при **одинаковом** thread count.
Per-thread считается как `aggregate ÷ threads` (приведено в `DRAFT.md`).

**О `scoreError`.** Это **99.9 % CI half-width**, а не стандартное
отклонение. При двух форках в него попадает разброс между форками (JIT,
page-cache). Строки с `scoreError / score > 10 %` сведены отдельной
таблицей (см. §7) — для публикационных рейтингов нужен `fork ≥ 5`.

---

## 4. Методология замеров

### 4.1. Профиль JMH (полный прогон)

| Параметр | Read / Write / Mixed / `*Unsafe` | `ScalingBenchmark` | `ReadLatencyBenchmark` |
|---|---|---|---|
| Mode | Throughput (`ops/s`) | Throughput | SampleTime (`ns/op`) |
| Forks | 2 | 2 | 2 |
| Warmup | 5 × 10 с | 5 × 1 с | 5 × 500 мс |
| Measurement | 5 × 10 с | 5 × 2 с | 5 × 1 с |

Значения берутся из блока `jmh { }` в
[`build.gradle.kts`](../build.gradle.kts) — он **переопределяет**
аннотации `@Warmup`/`@Measurement`/`@Fork` в исходниках бенчмарков
(аннотации — лишь дефолты для запуска без Gradle). JVM:
`-Xmx24g -Xms256m -XX:+UseG1GC`, OpenJDK 26.0.1.

### 4.2. Подготовка фикстуры

`@Setup` наполняет мап `range` (или `entries`) записями `i → i` до
начала замера. Замеряется только горячий цикл (`@Benchmark`), не
наполнение. Ключ на каждой операции — `ThreadLocalRandom.nextKey(range)`
(равномерно случайный), поэтому замер не «застревает» на одной
cache-line.

### 4.3. Boxing на горячем пути

`IntLongMap` принимает `Int`/`Long` → каждый `putM`/`getM`
**боксит ключ и значение** (`Integer.valueOf` / `Long.valueOf`). На
1 потоке аллокация боксов **доминирует** над разницей реализаций: все
укладываются в ≈ 15–18 M ops/s. Это сознательное упрощение (общий
интерфейс), но его надо держать в голове при чтении 1-thread чисел.

### 4.4. Mixed ≠ наивная смесь Read + Write

`MixedBenchmark` на каждой операции делает ветвление
`ThreadLocalRandom.nextDouble() < readShare`. Измеренная пропускная
способность **всегда ниже** наивного гармонического смешения
изолированных read- и write-throughput-ов из-за (а) лишнего RNG +
ветвления, (б) инвалидаций cache-line от переплетающихся записей,
(в) конкуренции за те же горячие линии bucket-массива.

### 4.5. Фикстуры `*Unsafe` держат два мапа сразу

`ReadBenchmarkUnsafe` / `WriteBenchmarkUnsafe` наполняют **одновременно**
`PlainHashMap` и OWN `ConcurrentHashMap` (оба по 1 048 576 записей).
Это **удваивает working-set** относительно `ReadBenchmark` (один мап), и
`readOwnSingle_thr01` оказывается ниже `read_thr01` OWN при идентичном
коде — артефакт фикстуры, а не реализации (см. `DRAFT.md` §6).

### 4.6. Latency = случайный ключ, не «горячий»

`ReadLatencyBenchmark.getSample` тянет случайный ключ на **каждый
sample** поверх 1 M записей. Медианная латентность ≈ 50 нс (не 20 нс):
случайный ключ обычно промахивается мимо L1, в 50 нс уже входит
типичный L2/L3-хоп + boxing.

### 4.7. Scaling на 100 M / 300 M = тест памяти

Каждая запись ≈ 80 Б (`Node` 32 Б + boxed `Integer` 24 Б + boxed
`Long` 24 Б, compressed oops). При 300 M записей — **≈ 24 ГБ живых
объектов**, что упирается в `-Xmx24g`; эти точки идут под давлением G1GC.
Их следует трактовать как **memory-system + GC** тест, а не как чистый
lookup.

---

## 5. Матрица бенчмарков

| Класс | Методы | Параметры | Строк JSON |
|---|---|---|---:|
| `ReadBenchmark` | `read_thr{01,02,04,08,16,32}` | `impl × range=1048576` | 18 |
| `WriteBenchmark` | `write_thr{01..32}` | `impl × range` | 18 |
| `MixedBenchmark` | `mixed_thr{01..32}` | `impl × readShare={0.2,0.5,0.8} × range` | 54 |
| `ScalingBenchmark` | `scalingRead_thr08` | `impl × entries={1k…300M}` | 21 |
| `ReadLatencyBenchmark` | `getSample` | `impl` | 3 |
| `ReadBenchmarkUnsafe` | `readUnsafe_thr01`, `readOwnSingle_thr01` | `range` | 2 |
| `WriteBenchmarkUnsafe` | `writeUnsafe_thr01`, `writeOwnSingle_thr01` | `range` | 2 |

`impl ∈ {OWN, JDK, SYNC}` для основных семейств; `*Unsafe` сравнивают
`PlainHashMap` с OWN. **Итого 118 строк** — полное покрытие, без
пропусков по `(thread × impl)` и `(entries × impl)`.

---

## 6. Как читать каждый график

Все графики живут в `docs/img/full/` и генерируются
[`scripts/plot_results.py`](../scripts/plot_results.py) из
`results/full/jmh-results.json`.

### 6.1. `throughput_threads_ReadBenchmark.png` / `..._WriteBenchmark.png`

Aggregate `ops/s` (log Y) против числа потоков; линия + маркеры +
error-bars (`scoreError`) на каждую реализацию. Как читать: наклон до
8 потоков ≈ масштабируемость на 8 физических ядрах; плато/просадка после
8 — SMT-территория и/или contention.

### 6.2. `throughput_threads_MixedBenchmark_rs{0.2,0.5,0.8}.png`

То же, но по одному графику на долю чтений. `rs=0.2` — write-доминирует,
`rs=0.8` — read-доминирует. Как читать: чем выше доля чтений, тем ближе
форма к read-кривой; чем ниже — тем сильнее упирается в write-локи.

### 6.3. `throughput_threads_ReadBenchmarkUnsafe.png` / `..._WriteBenchmarkUnsafe.png`

Однопоточные точки `PlainHashMap` (`HashMap`) и OWN из dual-фикстуры
(§4.5). Как читать: нижняя граница стоимости операции без синхронизации;
помнить про удвоенный working-set.

### 6.4. `unsafe_overhead_1thread.png`

Бар-чарт: `HashMap` против OWN/JDK/SYNC на 1 потоке (read- и
write-панели). Как читать: «сколько стоит синхронизация на 1 потоке».
Из-за boxing-а (§4.3) разница невелика.

### 6.5. `speedup_vs_sync_by_family.png`

Три панели (Read / Write / Mixed), `OWN`/`JDK` ÷ `SYNC`, log Y,
штриховая линия на 1.0. Mixed-панель = 6 линий (`OWN`/`JDK` × три `rs`).
Как читать: во сколько раз concurrent-реализация обгоняет глобальный лок;
самая чистая визуализация зазора OWN-vs-JDK по записям.

### 6.6. `scaling_loglog.png`

`entries` (1 k → 300 M) против read-throughput, log–log, error-bars.
Как читать: ищем **knee** — точку, где working-set покидает L3 и уходит
в DRAM (см. таблицу cache-иерархии в `DRAFT.md` §8). За 100 M точки идут
под GC.

### 6.7. `latency_percentiles.png`

p50 / p99 / p99.9 по трём реализациям (с легендой); mean и p100 — в
подписи. Как читать: медиана ≈ стоимость lookup-а; хвост p99.99+ —
JVM-паузы (GC / safepoints), общие для всех реализаций.

### 6.8. `mixed_heatmap_{OWN,JDK,SYNC}.png`

`readShare` × `threads`, цвет = throughput. **Внимание: у каждого
heatmap своя цветовая шкала** — кросс-сравнение реализаций по цвету
некорректно, смотреть числа в таблицах или `speedup_vs_sync`.

---

## 7. Аномалии и data quality

Полный список с доказательствами — в `DRAFT.md` §10. Здесь — категории.

**Tier 1 (структурные, не баги):**
- OWN записи не скейлятся за 8 потоков и проседают на 16 — потолок
  16 сегментных локов (§2.1). Лечится повышением `segmentCount`.
- OWN mixed-регрессия 15–19 % на 16 потоках — те же write-локи как точка
  сериализации, к которой выстраиваются читатели.

**Tier 2 (методологические артефакты):**
- `readOwnSingle_thr01` (68 M) < `read_thr01` OWN (92 M) — dual-фикстура
  `*Unsafe` удваивает working-set (§4.5), а не разница кода.
- 1-thread throughput всех реализаций ≈ равны — доминирует boxing (§4.3),
  не структура.
- Scaling-фикстура (2 с итерации) слегка занижает относительно
  `ReadBenchmark` (10 с) — кросс-проверка сходится в ±12 %.

**Tier 3 (шум измерения, `fork = 2`):**
- `read_thr01/02` JDK имеют 23 %/32 % CI (бимодальный JIT-инлайнинг между
  форками) — поэтому «суперлинейный» ×10 на 8 потоках для JDK — артефакт
  широкого знаменателя, а не реальный super-linear speedup.
- Скачок `2 t > 1 t` у SYNC на write/mixed — biased-lock / contention
  warmup транзиент.

---

## 8. jcstress: проверка корректности

Quick-mode (`jcstress { mode = "quick" }` в `build.gradle.kts`).
Все четыре теста проходят:

| Тест | Что проверяет |
|---|---|
| `PutGetStressTest` | `put` → concurrent `get` видит `0` или `1`, никогда не half-published мусор |
| `ConcurrentPutStressTest` | два `put` оставляют `10` или `20`, без потерянных обновлений |
| `MergeAtomicityStressTest` | два `merge(+1)` дают `2`, никогда `1` (linearizable) |
| `ResizeStressTest` | `get` сквозь `resizeLocked` видит старый **или** новый bucket-массив, никогда не наполовину достроенный (опирается на `@Volatile var buckets`) |

Это даёт уверенность, что throughput-числа сняты с **корректной**
структуры, а не с гонкой, которая «случайно быстрая».

---

## 9. Воспроизведение

```bash
./gradlew test --no-daemon -q                   # JUnit
./gradlew jcstress --no-daemon                  # concurrency stress (quick mode)
./gradlew jmh --no-daemon -Pjmh.heap=24g        # полная матрица 118 строк (часы)
python3 scripts/plot_results.py                 # графики → results/full/graphs/
./meta_run_flame.sh                             # flame для write_thr16 OWN/JDK
```

Лёгкий smoke (подмножество, 1 форк, итерации по 1 с):

```bash
./gradlew jmh --no-daemon -Pjmh.light=true -Pjmh.heap=1536m
```

Кастомный путь к JSON для plot-скрипта:

```bash
JMH_RESULTS_JSON=/path/to/jmh-results.json python3 scripts/plot_results.py
```
