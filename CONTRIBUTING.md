# Contributing

Issues and pull requests are welcome.

## Development

```bash
mvn clean test
mvn clean package
```

Use `gitlab.example` (or another fictional host) in examples and docs — do not commit real internal hosts or secrets.

## Pull requests

- Keep changes focused
- Add or update unit tests for behavior changes
- Update `README.md` when user-facing behavior changes
- Prefer pull requests into `main` (do not push directly); use PR labels so release-drafter / Jenkins CD can draft GitHub Releases
