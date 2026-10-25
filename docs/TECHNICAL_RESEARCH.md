# Техническое исследование — Torch Automation

Статус: **этап 1 (Meteor + Baritone + сборка + лицензии) — выполнен**. **Этап 2 (правила спавна/света 1.21.1) — выполнен; U-1…U-4 подтверждены по коду 1.21.1 (§8.8), U-5/U-6 открыты.**

**Цель продукта (уточнено пользователем 2026-10-25): чтобы ночью мобы не появлялись.**
Дата доступа ко всем источникам: 2026-10-25. Источники проверены по клонированным репозиториям на точных коммитах, а не только по README.

Сокращения ссылок:
- **M** = `https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6`
- **B** = `https://github.com/MeteorDevelopment/baritone/blob/d24e1d6ca3be828535f95a6c4ab86fcf5ffd678b`
- **T** = `https://github.com/MeteorDevelopment/meteor-addon-template/blob/49cdbd8c267d36664545a0b3e416a9f901266fa1`

## 1. Окружение пользователя (проверено)

| Что | Значение | Источник |
|---|---|---|
| Лаунчер | Prism/MultiMC instance `1.21.1` | `1.21.1/instance.cfg` |
| Minecraft | 1.21.1 | `1.21.1/mmc-pack.json` |
| Fabric Loader | 0.16.9 | `1.21.1/mmc-pack.json` |
| Моды (по именам файлов) | meteor-client-0.5.8, baritone-api-fabric-1.11.0, fabric-api 0.110.0+1.21.1, sodium 0.6.3, iris 1.8.1, lithium 0.14.3, litematica, malilib, worldedit 7.3.8, xaero minimap, jei, appleskin, architectury, modmenu | листинг `1.21.1/.minecraft/mods` |
| Java (система) | JDK 23.0.1 (`C:\Program Files\Java\jdk-23`), `JAVA_HOME` не задан | `java -version` |
| Gradle | не установлен глобально (будет gradle wrapper) | `Get-Command gradle` |
| git | 2.47.1; папка проекта **не** git-репозиторий | `git --version` |

`baritone-meteor-1.19.2…` и `baritone-meteor-1.20.1…` **отсутствуют** в `mods/` — в рантайме не участвуют.
Содержимое JAR-файлов **не открывалось** (по решению пользователя). Всё ниже о Baritone 1.11.0 — выводы по исходникам, не по бинарнику.

## 2. Meteor Client 0.5.8 — точный коммит

- Тег `v0.5.8` → коммит `37c5c5c640ba9c66721e824c62e84bf1223c9cd6` (2024-11-07 18:49 UTC).
- `M/gradle.properties`: `minecraft_version=1.21.1`, `yarn_mappings=1.21.1+build.3`, `loader_version=0.15.11`, `fapi_version=0.102.1+1.21.1`, `baritone_version=1.21`.
- `M/src/main/resources/fabric.mod.json`: `"license": "GPL-3.0"`, `"minecraft": ["1.21", "${minecraft_version}"]`, `java >=21`, entrypoint `client: MeteorClient`.
- Maven: `meteordevelopment:meteor-client:0.5.8` есть в `https://maven.meteordev.org/releases`; snapshot `0.5.8-20241107.185150-94` совпадает по времени с тегом.

**Вывод:** Meteor 0.5.8 действительно собран для MC 1.21.1 (подтверждено исходниками и Maven).

## 3. Как Meteor 0.5.8 работает с Baritone

### 3.1 Baritone НЕ встроен в Meteor
- `M/build.gradle#L65-L66`: `modCompileOnly "meteordevelopment:baritone:${baritone_version}-SNAPSHOT"` — только для компиляции, без `include`. В JAR Meteor Baritone не попадает.
- Значит, в рантайме используется **отдельный** Baritone-мод — у пользователя это `baritone-api-fabric-1.11.0.jar`.

### 3.2 Обнаружение и инициализация
- `M/src/main/java/meteordevelopment/meteorclient/pathing/PathManagers.java#L20-L39` — `@PreInit init()`:
  - если есть класс `meteordevelopment.voyager.PathManager` — используется Voyager;
  - если есть `baritone.api.BaritoneAPI` → `BaritoneUtils.IS_AVAILABLE = true` и (если Voyager нет) `INSTANCE = new BaritonePathManager()`.
- `PathManagers.get()` возвращает `IPathManager` — Meteor-абстракция над навигацией (`M/.../pathing/IPathManager.java`): `isPathing, pause, resume, stop, moveTo(BlockPos[, ignoreY]), moveInDirection, mine, follow, getTargetYaw/Pitch, getSettings`.
- `M/.../pathing/BaritonePathManager.java`:
  - `#L41-L67` конструктор: подписка на event bus Meteor; **рефлексией** ищет поле типа `Rotation` в реализации `ILookBehavior` (`#L46-L58`) — это зависимость от внутренностей Baritone; регистрирует собственный `IBaritoneProcess` (`#L66`).
  - `#L95-L102` `moveTo` → `getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos))` или `GoalXZ`.
  - `#L89-L92` `stop` → `getPathingBehavior().cancelEverything()`.
  - `#L79-L87` `pause/resume` — только флаг; `#L217-L247` внутренний `BaritoneProcess`: `isActive()=pathingPaused`, `onTick` → `clearAllKeys()` + `PathingCommand(null, REQUEST_PAUSE)`, `isTemporary()=true`, `priority()=0`.
- `M/.../systems/modules/Modules.java#L573-L576`: модули `Excavator` и `InfinityMiner` регистрируются только если `BaritoneUtils.IS_AVAILABLE`.
- `M/src/main/resources/meteor-client-baritone.mixins.json`: миксин `ComeCommandMixin` в Baritone.

### 3.3 Какой вариант Baritone ожидается
- Meteor компилируется против `meteordevelopment:baritone:1.21-SNAPSHOT` (форк MeteorDevelopment/baritone). В Maven snapshots есть `1.21-SNAPSHOT` и `1.21.1-SNAPSHOT`.
- Ветка форка `1.21.1` → коммит `d24e1d6c…` «Meteor changes» (2024-08-26) поверх апстрима cabaletta (merge PR #4466 «1.21.1-compat»). Отличия форка от апстрима (`git show d24e1d6c --stat`):
  - `B/fabric/src/main/resources/fabric.mod.json#L3`: mod id `baritone-meteor` (вместо `baritone`);
  - `B/fabric/src/main/java/baritone/launch/FabricMixinPlugin.java#L39,#L54`: миксины форка **отключаются**, если загружен мод с id `baritone` — т.е. форк рассчитан на сосуществование с оригинальным Baritone;
  - `B/src/api/java/baritone/api/Settings.java#L672`: `chatControl` по умолчанию `false`;
  - `gradle.properties`: в апстриме на этой точке было `mod_version=1.11.0` → форк переименовал в `1.21.1-SNAPSHOT`.
- **Публичный API (`baritone.api.*`) форка не изменён**, кроме значения по умолчанию одной настройки.

### 3.4 Что такое `baritone-api-fabric-1.11.0.jar` пользователя
- В cabaletta/baritone **нет тега и релиза `v1.11.0`** (`gh api …/releases/tags/v1.11.0` → 404). Релизы: `v1.11.1` — «For Minecraft 1.21.3 only», `v1.11.2` — «For Minecraft 1.21 and 1.21.1» (https://github.com/cabaletta/baritone/releases).
- Однако версия апстрима `1.11.0` — ровно та, на которой основан форк Meteor для 1.21.1 (см. 3.3). Вероятно, это сборка апстрима ветки 1.21/1.21.1 (CI/неофициальная). **Не подтверждено**: нужна проверка `fabric.mod.json` внутри JAR (id, `depends.minecraft`).
- То, что игра у пользователя запускается с этим JAR и Meteor, — косвенное, но не проверенное мной подтверждение совместимости.

### 3.5 Доступен ли API аддонам
Да. `baritone.api.BaritoneAPI` — публичный статический вход (`BaritoneAPI.getProvider().getPrimaryBaritone()`), классы грузятся общим Fabric classloader. Аддон может:
- либо идти через Meteor-абстракцию `PathManagers.get()` (покрывает `moveTo/stop/pause/isPathing`);
- либо через `baritone.api` напрямую (нужно для событий пути, своих процессов, контроля чужих задач).
Компилировать против API без включения в JAR: `modCompileOnly "meteordevelopment:baritone:1.21.1-SNAPSHOT"` (или `1.21-SNAPSHOT`, как Meteor).

### 3.6 Нужные нам части публичного API Baritone (проверено в исходниках ветки 1.21.1)
| Задача | API |
|---|---|
| Идти к позиции, откуда можно поставить факел | `ICustomGoalProcess.setGoalAndPath(Goal)`; `GoalNear(pos, r)` / `GoalGetToBlock` / `GoalBlock` |
| Статус | `IPathingBehavior.isPathing()`, `getGoal()`, `estimatedTicksToGoal()` |
| Отмена | `IPathingBehavior.cancelEverything()` / `forceCancel()` |
| События (успех/провал/отмена) | `IGameEventListener.onPathEvent(PathEvent)` через `IBaritone.getGameEventHandler().registerEventListener(...)`; `PathEvent.{AT_GOAL, CALC_FAILED, NEXT_CALC_FAILED, CANCELED, …}` |
| Чья задача сейчас активна | `IPathingControlManager.mostRecentInControl()`, `mostRecentCommand()` |
| Пауза без потери чужой цели | свой `IBaritoneProcess` (temporary, `REQUEST_PAUSE`) — как у Meteor |
| Ограничения поведения | `Settings.allowBreak` (по умолч. **true**), `allowPlace` (**true**), `allowInventory` (false), `allowParkour` (false), `maxFallHeightNoWater` (3), `blockReachDistance` (4.5), `antiCheatCompatibility` (true) — `B/src/api/java/baritone/api/Settings.java` |

Важно (`B/src/main/java/baritone/utils/PathingControlManager.java#L96-L100, #L181-L212`): процессы сортируются по `priority()`; при смене контролирующего «не temporary» процесса прошлый сегмент отменяется, остальным вызывается `onLostControl()`. Следовательно, `setGoalAndPath` в `CustomGoalProcess` **перезапишет** активную цель пользователя (`#goto`) и может перехватить управление у `mine`/`build`. Перед стартом нужно проверять `isPathing()`/`mostRecentInControl()` и требовать явного подтверждения.

`allowBreak/allowPlace = true` по умолчанию: Baritone может ломать/ставить блоки на пути — на сервере это может быть нежелательно. Настройки Baritone **глобальные** и сохраняются в его конфиг; менять их молча нельзя (решение — см. DECISIONS D-5).

## 4. Модули Meteor как образцы архитектуры

| Модуль | Что взять как образец | Ссылки |
|---|---|---|
| `Excavator` | выбор двух углов по `crosshairTarget` + keybind; рендер выделения через `Render3DEvent`; статус-машина `SEL_START → SEL_END → WORKING`; завершение по `!isActive()`; очистка в `onDeactivate` | `M/.../modules/world/Excavator.java#L25, #L75-L82, #L88-L93, #L111-L129, #L131-L142` |
| `InfinityMiner` | прямая работа с `IBaritone`; определение, какой процесс в контроле (`mostRecentInControl() instanceof IMineProcess`); `cancelEverything()` перед новой задачей | `M/.../modules/world/InfinityMiner.java#L100, #L122, #L204-L205, #L221-L225` |
| `AutoEat` | вежливая пауза чужого пути: `PathManagers.get().isPathing()` → `pause()`, затем `resume()` | `M/.../modules/player/AutoEat.java#L182-L186, #L214-L218` |
| `BlockUtils.place` | путь размещения блока: выбор стороны `getPlaceSide`, `mc.interactionManager.interactBlock(...)` (обычный клиентский путь), swing | `M/.../utils/world/BlockUtils.java#L87, #L134-L138, #L165` |
| `InvUtils` | поиск в хотбаре `findInHotbar(Items.TORCH)`, `swap/swapBack` | `M/.../utils/player/InvUtils.java#L82, #L154, #L165` |
| `Rotations.rotate(..., clientSide, ...)` | поворот головы; при `clientSide=false` поворот только в пакетах, без видимого поворота камеры | `M/.../utils/player/Rotations.java#L49` |

Замечания:
- `Excavator` вызывает `BaritoneAPI.getProvider()` в инициализаторе поля → такой модуль **нельзя** создавать без Baritone. У нас — регистрировать навигационную часть только при `BaritoneUtils.IS_AVAILABLE`, остальное (анализ, оверлей) должно работать без Baritone.
- `Excavator` останавливает Baritone через строковую команду `execute("stop")` — мы будем использовать типизированный API.
- Для прозрачности предлагаю видимый поворот (`clientSide=true`), а не «тихий» поворот только в пакетах — см. D-6.

## 5. Минимальный аддон Meteor 0.5.8 и зависимости сборки

Шаблон: `MeteorDevelopment/meteor-addon-template`, коммит `49cdbd8c…` «Update template to 1.21.1» (2024-10-02); следующий коммит уже переходит на 1.21.3.
- `T/gradle.properties`: `minecraft_version=1.21.1`, `yarn_mappings=1.21.1+build.3`, `loader_version=0.16.5`, `meteor_version=0.5.8-SNAPSHOT`.
- `T/build.gradle`: `fabric-loom 1.7-SNAPSHOT`; репозитории `maven.meteordev.org/releases` и `/snapshots`; `modImplementation "meteordevelopment:meteor-client:${meteor_version}"`; Java 21 (`options.release = 21`).
- `T/gradle/wrapper/gradle-wrapper.properties`: Gradle 8.8.
- `T/src/main/resources/fabric.mod.json`: entrypoint **`"meteor"`** → класс, наследующий `MeteorAddon`; `depends: meteor-client "*"`, `minecraft`, `java >=21`.
- `T/src/main/java/com/example/addon/AddonTemplate.java`: `onInitialize()` (регистрация модулей/команд/HUD), `onRegisterCategories()`, `getPackage()` (обязателен — используется `ReflectInit` для `@PreInit/@PostInit`, `M/.../utils/ReflectInit.java#L25-L38`).
- Загрузка аддонов: `M/.../addons/AddonManager.java#L66` — `getEntrypointContainers("meteor", MeteorAddon.class)`.
- Лицензия шаблона — **CC0** (`T/LICENSE`).

Рекомендуемые зависимости нашего аддона:
```
minecraft 1.21.1, yarn 1.21.1+build.3, fabric-loader 0.16.5+ (у пользователя 0.16.9)
modImplementation  meteordevelopment:meteor-client:0.5.8          (release, вместо -SNAPSHOT)
modCompileOnly     meteordevelopment:baritone:1.21.1-SNAPSHOT     (только API, не включать в JAR)
fabric-loom 1.7-SNAPSHOT, Gradle wrapper 8.8, Java release 21
```
Риски сборки:
1. **Подтверждено запуском** (временная копия шаблона в scratchpad, 2026-10-25): `gradlew` (Gradle 8.8) на JDK 23 падает с `Unsupported class file major version 67`; с Gradle 8.10.2 падает декомпилятор Loom 1.7 (та же ошибка). Решение: **установить JDK 21** параллельно с JDK 23 и запускать сборку на нём (Fabric для 1.21.1 требует Java 21). Обновлять Loom/Gradle ради JDK 23 не будем. Конфигурация проекта при проверке не менялась.
2. `-SNAPSHOT` артефакт Baritone может быть пересобран; зафиксировать проверку хеша/версии.

## 6. Лицензии (факты из текстов, не юридическое заключение)

| Компонент | Лицензия | Источник |
|---|---|---|
| Meteor Client 0.5.8 | GPL-3.0 | `M/LICENSE` (674 строки GPLv3), `fabric.mod.json "license": "GPL-3.0"` |
| Baritone (форк и апстрим) | LGPL-3.0 + «LICENSE-Part-2.jpg» (картинка «No anime»; README: «LGPL-3.0 with anime exception») | `B/LICENSE`, `B/LICENSE-Part-2.jpg`, `B/README.md#L22` |
| Шаблон аддона | CC0-1.0 | `T/LICENSE` |

Наблюдения:
- README Meteor (`M/README.md#L52-L58`): при использовании **любого** кода Meteor производная работа должна быть открыта и лицензирована под той же лицензией.
- Наш аддон наследует `MeteorAddon`/`Module` и вызывает API Meteor (GPL-3.0). Распространённая позиция FSF: такой плагин образует производную работу → безопаснее всего лицензировать аддон под **GPL-3.0**. Это не юридическое заключение.
- Baritone мы не включаем в JAR (только `compileOnly`) — используем через публичный API; LGPL-3.0 это допускает. Смысл и юридическая сила «anime»-условия неясны — флаг для пользователя, на распространение без бандлинга, вероятно, не влияет.
- Код из Meteor/Baritone **не копируем** до утверждения лицензии (D-1). Используем только их публичные API.

## 7. Не проверено / открытые вопросы (этап 1)
1. Точное содержимое `baritone-api-fabric-1.11.0.jar` (mod id, `depends.minecraft`, происхождение) — нужно прочитать только его `fabric.mod.json` (с разрешения пользователя).
2. Сборка на JDK 21 — не запускалась (JDK 21 не установлен).
3. Поведение Baritone на шаге вверх/вниз на 1 блок, `blockReachDistance` и `GoalNear` в реальной игре — только ручной тест.
4. Воздействие Lithium/Sodium на нас — не исследовано (ожидается: только рендер оверлея).

## 8. Естественный спавн враждебных мобов и свет — Java 1.21.1, Overworld

### 8.0 Источники и как проверена привязка к 1.21.1
- Minecraft Wiki описывает **текущую** версию (на 2026-10 уже 26.x). Поэтому для каждого правила проверялся раздел History: изменение принимается, только если оно сделано **до или в 1.21.1** и не отменено до 1.21.1. Изменения после 1.21.1 отмечены отдельно и **не** применяются.
- Yarn Javadocs 1.21.1+build.3 (https://maven.fabricmc.net/docs/yarn-1.21.1+build.3/) дают только **имена и сигнатуры** классов/методов, без тел и почти без описаний. Логика методов по ним не подтверждается.
- Страницы: [Mob spawning](https://minecraft.wiki/w/Mob_spawning), [Light](https://minecraft.wiki/w/Light), [Dimension type](https://minecraft.wiki/w/Dimension_type), [Slime](https://minecraft.wiki/w/Slime), [Drowned](https://minecraft.wiki/w/Drowned), [Phantom](https://minecraft.wiki/w/Phantom), [Patrol](https://minecraft.wiki/w/Patrol), [Pillager](https://minecraft.wiki/w/Pillager), [Witch](https://minecraft.wiki/w/Witch), [Guardian](https://minecraft.wiki/w/Guardian), [Java Edition 1.21](https://minecraft.wiki/w/Java_Edition_1.21), [Java Edition 1.21.2](https://minecraft.wiki/w/Java_Edition_1.21.2). Доступ 2026-10-25.

Изменения в истории Mob spawning между 1.18 и 1.21.1, влияющие на враждебных мобов: **только 1.18** («Block light level now must be 0 for many hostile mobs to spawn», Experimental Snapshot 1) и 1.18.2 (водные мобы: блок над ними — не waterlogged вода). После 1.21.1: 1.21.2 — летучие мыши на любой высоте (не враждебные), Creaking (экспериментальный, в 1.21.1 его нет), 1.21.9 — gamerule `spawnMonsters`. **Для 1.21.1 их не учитываем.**

### 8.1 Свет: что есть что
| Величина | Определение (Java) | Источник |
|---|---|---|
| Block light (0–15) | от источников, flood fill, −1 за шаг (taxicab), с учётом непрозрачности и направленной непрозрачности блоков (плиты, ступени и т.п.). Факел — 14 (сам блок-источник 15 только у полнотелых источников) | Light |
| Sky light (0–15) | 15 под открытым небом **и днём, и ночью** (хранимое значение не зависит от времени); вниз через прозрачные блоки без потерь, вбок/вверх −1; вода, лёд, листва, паутина и т.п. уменьшают sky light на 1 | Light |
| Internal sky light | sky light минус затемнение от времени/погоды: в полдень 15, дождь 12, гроза 10, полночь 4; при sky light *s* < 15 вычитается (15 − *s*) | Light |
| Internal light (Overworld) | `max(internal sky light, block light)` | Light |
| Отображаемая яркость | рендер (гамма, smooth lighting, шейдеры Iris) — **к спавну отношения не имеет** | Light |

API 1.21.1 (Yarn, только сигнатуры): `BlockRenderView.getLightLevel(LightType, BlockPos)`, `BlockRenderView.getBaseLightLevel(BlockPos, int ambientDarkness)`, `BlockRenderView.getLightingProvider()`, `BlockRenderView.isSkyVisible(BlockPos)` (javadoc: «returns true if the sky light level at pos is the maximum, 15»), `World.getAmbientDarkness()`, `World.calculateAmbientDarkness()`, `World.isThundering()`, `World.isRaining()`, `World.getTimeOfDay()`; `AbstractBlockState.getLuminance()` («Returns the light level emitted by this block state»), `getOpacity`, `allowsSpawning(BlockView, BlockPos, EntityType)`, `isSideSolidFullSquare`, `getCollisionShape`.

### 8.2 Общее правило света для «монстров» в Overworld
- Тип измерения `minecraft:overworld`: `monster_spawn_block_light_limit = 0` — «Block light level must be less than or equal to this value for monsters to spawn»; `monster_spawn_light_level = uniform 0..7` — порог, с которым сравнивается internal light. Поля добавлены в 1.19-pre1, позже не менялись (Dimension type).
- Итог для большинства враждебных мобов: **block light = 0** И **internal light ≤ случайное 0..7**. Значит, в Overworld **любой block light ≥ 1** в точке спавна блокирует эти виды (Light, Mob spawning: «internal sky light is 7 or less and block light is 0»).
- В грозу враждебные мобы спавнятся «as if the internal sky light level were actually 5» (Light, сноска) — поэтому под открытым небом в грозу спавн возможен и днём. Блоковый свет ≥ 1 при этом всё равно блокирует.
- Метод: `HostileEntity.isSpawnDark(ServerWorldAccess, BlockPos, Random)`, `HostileEntity.canSpawnInDark(...)`, `HostileEntity.canSpawnIgnoreLightLevel(...)` — **существуют в 1.21.1 (Yarn)**; точный порядок проверок внутри **не подтверждён** (§8.7, U-1).

### 8.3 Поверхность и пространство (общие для «наземных» монстров)
По Mob spawning:
- Блок под мобом: «solid, complete, top surface» (верхние плиты и перевёрнутые ступени подходят; soul sand и mud — тоже). Не подходят: стекло, бедрок, барьер, люки, chorus flower, леса (scaffolding), медная решётка (copper grate, 1.21), листва (кроме оцелотов/попугаев), лёд (только белые медведи).
- Блок под мобом не должен излучать свет ≥ 14 (исключения: jack o'lantern, горящая redstone lamp); не должен быть жидкостью.
- Блок, в котором стоит моб: не полный коллизионный блок, не компонент передачи редстоун-сигнала, не жидкость; рельсы запрещены тегом `prevent_mob_spawning_inside`; моб не должен получать урон в блоке (огонь, wither rose, ягодный куст, порошковый снег).
- Хитбокс моба не должен пересекаться с твёрдыми блоками → зависит от размеров вида (паук шире 1 блока, но ниже; эндермен ~3 блока высотой).
- Методы 1.21.1 (Yarn): `SpawnHelper.isClearForSpawn(BlockView, BlockPos, BlockState, FluidState, EntityType)`, `SpawnLocationTypes.ON_GROUND / IN_WATER / IN_LAVA / UNRESTRICTED`, `AbstractBlockState.allowsSpawning(...)`. Тела не подтверждены (U-2, U-3).

### 8.4 Матрица: Overworld, естественный спавн, что даёт свет
| Моб / механизм | Условие по свету (1.21.1) | Где/на чём | Что делает факел | Источник |
|---|---|---|---|---|
| Zombie, Skeleton, Creeper, Spider, Enderman, Witch (вне хижины) | block = 0, internal ≤ rand(0..7) | твёрдая верхняя грань, место по хитбоксу | **предотвращает** при block ≥ 1 | Light, Mob spawning, Witch |
| Husk (пустыни), Stray (снежные биомы) | как выше + **нужен доступ к небу** (sky) | поверхность | **предотвращает** при block ≥ 1 | Mob spawning («until 26.4» — в 1.21.1 действует) |
| Cave spider | — естественно **не** спавнится (только спавнеры заброшенных шахт) | — | не применимо | Light |
| Drowned | block = 0, internal ≤ 7; вода высотой ≥ 2; океаны/дрипстоун — ниже Y 58; реки — без ограничения по Y | вода | **предотвращает** при block ≥ 1 в воде | Drowned |
| Slime — болото / мангровое болото | internal light ≤ rand(0..7) (**не** block = 0), Y 51–69, фаза луны, 50 % | поверхность | нужен **block ≥ 8** (не «≥ 1») | Slime |
| Slime — слайм-чанк | **не зависит от света и погоды**, Y < 40, ~1/10 чанков | | **не предотвращает** | Slime |
| Patrol (разбойники) | block ≤ 8, sky не важен; 24–48 блоков от игрока; не в грибных полях | высший твёрдый блок | нужен **block ≥ 9** | Patrol, Pillager |
| Pillager у аванпоста | block ≤ 8, объём 72×54×72 | непрозрачный блок | нужен **block ≥ 9** | Pillager |
| Witch в хижине ведьмы | общее правило монстров (`canSpawnInDark`) | объём хижины 7×7×9 | **предотвращает** при block ≥ 1 (код, §8.8) | Witch, код |
| Guardian (океанский монумент) | **от света не зависит**; 95 % провал при видимом небе | вода + вода снизу | **не предотвращает** (код, §8.8) | Guardian, код |
| Phantom | зависит от игрока: ночь/гроза, Y > 62, небо над **игроком**, статистика сна | над игроком | **не предотвращает** | Phantom |
| Zombie villager / Zombie horse, Skeleton horse (trap) | zombie horse — только на grass; skeleton trap — от молнии | | частично / нет | Mob spawning |
| Спавнеры, trial spawners, рейды, Warden (shrieker), спавн при генерации структур | собственные правила | | **не предотвращает** (вне области продукта) | Mob spawning, Java Edition 1.21 |
| Mushroom fields, deep dark | враждебные мобы естественно не спавнятся | | не требуется | Witch, Mob spawning |

### 8.5 Что знает клиент, а что — только сервер
- Клиент получает свет от сервера: пакеты `Chunk Data and Update Light` и `Update Light` несут для каждой секции чанка массивы **Sky Light** и **Block Light** (2048 байт, полбайта на блок) и маски «пустых» секций ([Java Edition protocol/Packets](https://minecraft.wiki/w/Java_Edition_protocol/Packets) — документирует протокол 777 (26.3), а не 767 (1.21.1); формат Light Data предполагается тем же, **не подтверждено** для 767).
- Клиент имеет свой `LightingProvider` (`BlockRenderView.getLightingProvider()`), поэтому `getLightLevel(LightType.BLOCK/SKY, pos)` на клиенте даёт **реальные значения**, а не рендер-яркость. Пересчитывает ли клиент свет сам после локального изменения блока до прихода серверного пакета — **не подтверждено** (U-5).
- Время суток и погода синхронизируются сервером → internal sky light можно вычислить на клиенте.
- Только сервер знает: seed (→ слайм-чанки), сложность по области, мобкап, состояние сна игроков, кастомные правила/плагины/датапаки (могут переопределить `dimension_type`, биомы, спавн), реальные результаты спавна.

### 8.6 Выводы для модели продукта
1. **Основной режим** (рекомендация): точка (блок ног) — кандидат, если там может встать наземный монстр (поверхность + место по §8.3/§8.8); она безопасна, если **block light ≥ 1 в блоке ног**. Это закрывает zombie/skeleton/creeper/spider/enderman/witch/husk/stray/drowned. Sky light и время суток в основном режиме **не** используются для решения «безопасно»: днём точка может быть безопасна, а ночью — нет, а факел защищает всегда.
2. Факел (14) даёт block ≥ 1 на расстоянии до 13 шагов (taxicab) по пропускающим свет блокам — покрытие одного факела большое; поэтому планировщик должен опираться на **реальное распространение света**, а не на радиус.
3. **Профили сверх основного** (решение пользователя): «болотные слаймы» (block ≥ 8 в болотах на Y 51–69), «патрули/аванпост» (block ≥ 9).
4. **Не предотвращается** и должно быть сказано в UI: слайм-чанки, фантомы, спавнеры, trial spawners, рейды, Warden, мобы из структур при генерации, гроза под открытым небом не отменяет правило block ≥ 1 (отменяет только sky-часть).
5. Неизвестные (незагруженные) чанки помечаются «неизвестно», не «безопасно».

### 8.7 Что осталось неподтверждённым (нужна точечная проверка по коду конкретных методов)
| ID | Вопрос | Почему важно | Что посмотреть |
|---|---|---|---|
| U-1 | Точный порядок проверок в `isSpawnDark`: есть ли предварительная случайная проверка sky light; как именно применяется грозовое затемнение | влияет только на вероятность спавна, **не** на правило «block ≥ 1 блокирует» | `HostileEntity.isSpawnDark` |
| U-2 | Точная проверка поверхности: `allowsSpawning` по умолчанию (top face full square? luminance < 14?), какие блоки её переопределяют | валидность кандидатов | `AbstractBlock.Settings.allowsSpawning`, `SpawnLocationTypes.ON_GROUND` |
| U-3 | Точная проверка пространства: `isClearForSpawn` (redstone, жидкость, тег, урон) | валидность кандидатов | `SpawnHelper.isClearForSpawn` |
| U-4 | Какой предикат спавна зарегистрирован у каждого вида: guardian, witch, slime, drowned, husk, stray, zombie villager | матрица §8.4 для guardian/witch | `SpawnRestriction` (регистрации) |
| U-5 | Пересчитывает ли клиент свет локально после изменения блока (до серверного пакета) | проверка «факел действительно поставлен и светит» | `ClientChunkManager` / ручной тест в игре |
| U-6 | Формат light data в протоколе 767 (1.21.1) | только для понимания, API не нужен | Protocol History / тест |

U-1…U-4 закрыты в §8.8. U-5 — ручной тест в игре. U-6 — не критично.

### 8.8 Проверка по коду 1.21.1 (U-1…U-4)
Источник: исходники Minecraft 1.21.1, декомпилированные Loom (Vineflower) с Yarn `1.21.1+build.3` (файл `minecraft-merged-…-1.21.1-net.fabricmc.yarn.1_21_1.1.21.1+build.3-v2-sources.jar` из пробного запуска; пересборка не делалась). Прочитаны только перечисленные методы. Номера строк — в декомпилированном тексте, могут отличаться у другого декомпилятора.

**U-1 — `HostileEntity.isSpawnDark` (стр. 87–100) — ПОДТВЕРЖДЕНО:**
1. `getLightLevel(SKY, pos) > random.nextInt(32)` → отказ (случайная проверка хранимого sky light 0..31);
2. `limit = dimension.monsterSpawnBlockLightLimit()`; если `limit < 15` и `getLightLevel(BLOCK, pos) > limit` → отказ (Overworld: limit 0 → **block ≥ 1 всегда запрещает**);
3. `j = isThundering ? getLightLevel(pos, 10) : getLightLevel(pos)` (internal light; в грозу ambient darkness 10 → sky-часть ≤ 5);
4. разрешено, если `j <= monsterSpawnLightTest().get(random)` (Overworld 0..7).
`pos` — блок, где стоят **ноги** моба (не блок пола). `canSpawnInDark` (стр. 102–106) = не Peaceful && (trial spawner || `isSpawnDark`) && `canMobSpawn`.
**Ночью** (internal sky ≈ 4 под открытым небом) шаги 1, 3, 4 почти не мешают спавну; надёжно мешает только шаг 2 — **block light ≥ 1 в блоке ног**.

**U-2 — поверхность — ПОДТВЕРЖДЕНО:**
- `SpawnLocationTypes.ON_GROUND.isSpawnPositionOk`: в пределах world border; блок **под** ногами `allowsSpawning(world, down, type)`; блок ног **и** блок над ним проходят `SpawnHelper.isClearForSpawn`.
- По умолчанию (`AbstractBlock.Settings`, стр. 1729–1732): `isSideSolidFullSquare(UP) && getLuminance() < 14`.
- Переопределения в `Blocks`: `never` — все люки (trapdoor), стекло и окрашенное стекло, tinted glass, барьер, бедрок, chorus flower, scaffolding, copper grate (и копии настроек); `always` — carved pumpkin, jack o'lantern, redstone lamp, soul sand, mud; листва — только ocelot/parrot; лёд/frosted ice — только polar bear; magma block — только огнестойкие (`isFireImmune`).

**U-3 — пространство — ПОДТВЕРЖДЕНО:**
- `SpawnHelper.isClearForSpawn` (стр. 304–314): отказ, если блок `isFullCube`, `emitsRedstonePower`, содержит жидкость, в теге `prevent_mob_spawning_inside`, или `entityType.isInvalidSpawn(state)` (урон от блока).
- `SpawnHelper.canSpawn` (стр. 204–227): после `isSpawnPosAllowed` и предиката вида проверяется `world.isSpaceEmpty(type.getSpawnBox(x+0.5, y, z+0.5))` — хитбокс конкретного вида не пересекает коллизии.
- `MobEntity.canSpawn(WorldView)` (стр. 914–916): нет жидкости в хитбоксе и нет пересечения с сущностями.
- Расстояние: `isAcceptableSpawnPosition` (стр. 194–202) — дальше 24 блоков (576 = 24²) от игрока и от точки спавна мира.

**U-4 — предикаты видов (`SpawnRestriction`, стр. 79–153) — ПОДТВЕРЖДЕНО:**
| Вид | Место | Предикат | Итог по свету |
|---|---|---|---|
| zombie, skeleton, creeper, spider, cave spider, enderman, **witch**, zombie villager, bogged, ravager | ON_GROUND | `HostileEntity::canSpawnInDark` | block ≥ 1 запрещает |
| evoker, vindicator | UNRESTRICTED | `canSpawnInDark` | block ≥ 1 запрещает |
| husk | ON_GROUND | `canSpawnInDark` && `isSkyVisible(pos)` | block ≥ 1 |
| stray | ON_GROUND | `canSpawnInDark` && небо видно (сквозь powder snow) | block ≥ 1 |
| drowned | IN_WATER | вода под и в pos, `isSpawnDark`, 1/15 (частые биомы) или 1/40 + глубина | block ≥ 1 |
| **guardian, elder guardian** | IN_WATER | `(1/20 || !isSkyVisibleAllowingSea) && !Peaceful && вода && вода снизу` | **от света не зависит** |
| slime | ON_GROUND | болото: тег `allows_surface_slime_spawns`, 50 < Y < 70, 50 %, `< moonSize`, `getLightLevel(pos) <= nextInt(8)`; слайм-чанк: 1/10, Y < 40, **без света** | болото: block ≥ 8; чанк: не предотвращается |
| pillager | ON_GROUND | `getLightLevel(BLOCK) > 8` → отказ, иначе без света | block ≥ 9 |
| phantom, warden | UNRESTRICTED | `MobEntity::canMobSpawn` (свой спавнер) | не предотвращается |

Ведьма в хижине использует тот же зарегистрированный `canSpawnInDark` → **block ≥ 1 запрещает** (матрица §8.4 уточнена). Стражи монумента — **светом не предотвращаются**.

### 8.9 Блоки, на/в которых враждебные мобы не появляются (Overworld, 1.21.1)
Источники: [Tutorial:Spawn-proofing](https://minecraft.wiki/w/Tutorial:Spawn-proofing) (без привязки к версии, помечен как незавершённый) — **сверено с кодом 1.21.1**: `AbstractBlock.Settings.allowsSpawningPredicate` (стр. 1729–1732), переопределения в `Blocks`, `SpawnHelper.isClearForSpawn`, `SpawnHelper.canSpawn` (`isSpaceEmpty(spawnBox)`), `EntityType.isInvalidSpawn` (стр. 1210–1218), `SnowBlock` (стр. 26–80), `CarpetBlock` (стр. 13).

Три независимые причины, по которым место не опасно (достаточно любой):

**А. Пол (блок под ногами) не годится** — `allowsSpawning` = false:
- верхняя грань не полный квадрат (`isSideSolidFullSquare(UP)` false): нижние плиты, ступени «правильной» ориентации, грядка (farmland), тропинка (dirt path), заборы, стены, сундуки и другие неполные блоки;
- блок светит на ≥ 14: glowstone, sea lantern, shroomlight, froglight (все 3), beacon и т.п. (список блоков с luminance 14–15 из `Blocks`);
- явный запрет `never`: все люки, стекло, окрашенное и тонированное стекло, барьер, бедрок, scaffolding, chorus flower, медная решётка;
- только для отдельных видов (враждебные «тёмные» мобы Overworld — **нет**): листва (оцелот, попугай), лёд и frosted ice (белый медведь), magma block (огнестойкие мобы).
- **Исключения — на них спавн разрешён:** верхние плиты, двойные плиты, перевёрнутые ступени; `always` — soul sand, mud, carved pumpkin, jack o'lantern, redstone lamp (даже включённая).

**Б. Блок ног или блок над ним не годится** — `isClearForSpawn` = false:
- полный куб; источник/проводник редстоун-сигнала (`emitsRedstonePower`: кнопки, рычаги, нажимные плиты, редстоун-пыль и др.); жидкость (вода блокирует наземных мобов); тег `prevent_mob_spawning_inside` (рельсы);
- блок наносит урон (`isInvalidSpawn`): огонь/магма-урон для неогнестойких, wither rose, sweet berry bush, кактус, powder snow.

**В. Хитбокс моба пересекается с коллизией блока ног** — `isSpaceEmpty(spawnBox)` = false:
- ковры (коллизия 1/16 блока);
- снег 2–7 слоёв (коллизия `layers − 1`, т.е. ненулевая); **1 слой снега спавн не блокирует** (коллизия пустая); 8 слоёв — полная верхняя грань, на нём спавн разрешён;
- любые блоки с коллизией в нижней части блока ног; хитбокс зависит от вида (паук шире, эндермен выше).

Расхождения вики с кодом:
- вики: «Mobs also cannot spawn on top of transparent blocks» — в коде **нет общего правила прозрачности**; запрещены конкретные блоки (стекло, листва и др. из списка выше). Модель берёт решение из кода, а не по признаку «прозрачный».
- вики относит ковры к «блокам, на которых нельзя появиться» (неполная высота); в коде причина иная — коллизия в блоке ног (п. В). Результат одинаковый.

**Вывод для мода:** все три проверки выполняются ванильными методами на клиенте (`allowsSpawning`, `isClearForSpawn`, `getSpawnBox` + коллизии), поэтому пол из «неспавнящих» блоков автоматически не считается опасным, и факелы туда не планируются. Списки выше — для документации и тестов, а не для захардкоженной таблицы в коде (правило D-24).
