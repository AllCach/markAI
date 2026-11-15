# Проверка beta-кандидата

Исходники `0.11.3-beta.1` находятся в `dev-beta`. `build` с проверками `check` прошёл на JDK 25; серверный `runGameTest` прошёл 44/44 сценария; `betaDist verifyBetaDist` проверил архив и SHA-256. Клиентский GameTest, production smoke, beta soak и установка из нового ZIP **на этом пакете ещё не подтверждены**.

Для кандидата нужно на одном зафиксированном коммите выполнить `clean build check`, `runGameTest`, `runClientGameTest`, `productionJarSmokeTest`, `runBetaGameTest` и `betaDist verifyBetaDist`. Двухминутный soak должен завершиться и в Gradle, и в его JSON-отчёте. После этого — два настоящих клиента, копия старого мира, диалог с моделью и без неё, ручная проверка владельцем. Отчёт кандидата: [PRE_RELEASE_0.11.3.md](PRE_RELEASE_0.11.3.md).

В архив не входят миры, `config/markai.json`, переписка, ключи, локальная модель и инструменты. Если отправляете логи с ошибкой, удалите личные сообщения и секреты.
