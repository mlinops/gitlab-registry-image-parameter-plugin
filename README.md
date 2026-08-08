# GitLab Registry Image Parameter

Jenkins build parameter that lists **container image tags** from a GitLab Container Registry project.

Pipeline symbol: `gitLabRegistryImage` · Version: `1.0-SNAPSHOT`

## Requirements

- Jenkins 2.440.3 or newer (see `pom.xml`)
- Optional GitLab credentials: **Username with password** or **Secret text** (personal access token)
- Token scope for private projects: typically `read_registry` / `read_api` (depends on your GitLab setup)

## Pipeline example

Generate from **Pipeline Syntax** → Sample Step **`properties: Set job properties`** → **This project is parameterized** → **Add Parameter** → **GitLab Registry Image Tag**.

Required fields: `name`, `repoUrl`, `imageName`. Other fields are optional.

Template: [`examples/PipelineSyntax.gitLabRegistryImage.groovy`](examples/PipelineSyntax.gitLabRegistryImage.groovy)  
Full job example: [`examples/Jenkinsfile.images.plugin`](examples/Jenkinsfile.images.plugin)

```groovy
properties([
  parameters([
    gitLabRegistryImage(
      name: 'IMAGE_TAG',
      repoUrl: 'https://gitlab.example/group/project.git',
      imageName: 'my-service',
      credentialsId: 'gitlab_api_token',
      defaultVersion: 'none'
    )
  ])
])
```

Replace `gitlab.example` with your GitLab host.

## Configuration fields

| Field | Description |
|-------|-------------|
| `name` | **Required.** Environment variable name (e.g. `IMAGE_TAG`) |
| `repoUrl` | **Required.** Project URL (`http://` or `https://`) |
| `imageName` | **Required.** Docker-style image name in the registry |
| `description` | Optional plain-text help on the Build With Parameters page |
| `credentialsId` | Optional. Empty = public project |
| `skipSslVerification` | Optional. Disable TLS verification (self-signed) — **insecure** |
| `defaultVersion` | Optional preselected value; added to the list if missing |
| `exclude` / `regex` | Optional Java regex to drop / keep tags |
| `perPage` | Values per API page (1–100, default 50) |
| `maxPages` | Max pages to fetch (1–50, default 2) |
| `maxRows` | Max values in the dropdown (1–500, default 30) |
| `sortMode` | `NONE` / `ASC` / `DESC` / `*_SMART` |
| `connectTimeoutMs` / `readTimeoutMs` | HTTP timeouts |

**Deprecated:** `include` migrates to `defaultVersion`. Prefer `defaultVersion`.

## Credentials

1. Jenkins → **Manage Credentials** → add **Username with password** or **Secret text**
2. Select that credential in the parameter form (`credentialsId`)
3. UI order: **GitLab Repo URL** (+ **Test connection**) → Skip SSL → Credentials

URL validation checks format and blocks unsafe targets; live connectivity is verified with **Test connection**.

## Security notes

- Build-page AJAX sends only the parameter name (not credentials or URLs in the request body beyond job binding).
- `skipSslVerification=true` enables trust-all TLS — use only for trusted internal GitLab.
- Loopback, link-local, and cloud metadata addresses are blocked. Private RFC1918 hosts are allowed for typical self-hosted GitLab.

## License

MIT — see [`LICENSE`](LICENSE).
