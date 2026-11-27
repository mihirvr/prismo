# Contributing to Prismo

Thank you for wanting to help. Prismo is a one-person project, so the easiest way to help is a clear issue.

## Reporting a bug or asking for something

Open an issue: https://github.com/mihirvr/prismo/issues/new/choose

- **Bug report:** what you did, what you expected, what happened instead, your Prismo version (Settings › About) and
  your operating system. A screenshot helps. Prismo's log is in `%LOCALAPPDATA%\Prismo\logs` on Windows and
  `~/.local/state/prismo/logs` on Linux.
- **Idea:** what you would like Prismo to do, and why it would help you.
- **Security problem:** don't open an issue; see [SECURITY.md](SECURITY.md).

## Sending code

Please open an issue first and say what you would like to change, so we can agree on it before you spend time on it.

1. Build and run it: [docs/RUN_LOCALLY.md](docs/RUN_LOCALLY.md). How the code is laid out and the rules it follows:
   [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
2. Keep a change small and about one thing. Add or update tests; `./gradlew test` must pass.
3. Write comments, docs and messages in plain, simple English.
4. Never put personal paths, user names or private data in code, tests or docs.

## Licence of contributions

Prismo is licensed under the [Sustainable Use License 1.0](LICENSE). If you send code, it comes under the same licence
as Prismo, and you agree that Prismo's author may also release it under other terms later.

## Behaviour

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).
