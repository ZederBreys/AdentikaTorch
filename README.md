# Torch Automation

Аддон для Meteor Client 0.5.8 (Minecraft Java 1.21.1, Fabric): умная расстановка факелов против появления враждебных мобов из-за темноты в Overworld.

Статус: **M0** — пустой каркас (модуль `torch-highlight` пока ничего не делает). Концепция — `docs/PRODUCT_SPEC.md`, план — `docs/IMPLEMENTATION_PLAN.md`.

## Сборка
Нужен **JDK 21** (Gradle 8.8 / Loom 1.7 не запускаются на JDK 23). JDK 21 установлен в `C:\Users\ZederBreys\.jdks\jdk-21.0.12.1+1`; глобальный `JAVA_HOME` не меняется — он задаётся только для команды.

PowerShell:
```powershell
$env:JAVA_HOME = "C:\Users\ZederBreys\.jdks\jdk-21.0.12.1+1"; .\gradlew build
```

Git Bash:
```bash
JAVA_HOME="/c/Users/ZederBreys/.jdks/jdk-21.0.12.1+1" ./gradlew build
```

Результат: `build/libs/torch-automation-<версия>.jar` (содержит аддон и модуль `core`; Meteor и Baritone не включаются).
Только тесты ядра: `.\gradlew :core:test`.

## Установка в игру
Скопировать JAR в `mods/` инстанса рядом с `meteor-client-0.5.8.jar`. Аддон не выполняет никаких действий при загрузке.

## Структура
| Путь | Что |
|---|---|
| `core/` | чистая Java без Minecraft: свет, модель спавна, планировщик (unit-тесты JUnit 5) |
| `src/main/java/adentika/torch/` | аддон Meteor: модули, адаптеры к Minecraft/Baritone |
| `docs/` | исследование, решения, план, классификация блоков |
| `1.21.1/` | игровой инстанс пользователя — **не входит в репозиторий** (`.gitignore`) |

## Лицензия
GPL-3.0 (`LICENSE`), см. `docs/DECISIONS.md` D-1. Иконка-заглушка — из `meteor-addon-template` (CC0).
