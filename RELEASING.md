# Releasing

`h2histogram` is published to Maven Central automatically by the
[`release.yml`](.github/workflows/release.yml) GitHub Actions workflow via the
[Central Publisher Portal](https://central.sonatype.com), whenever a GitHub
Release is published.

## Cutting a release

1. **Bump the version.** Edit `<version>` in [`pom.xml`](pom.xml), dropping the
   `-SNAPSHOT` suffix (e.g. `0.1.0-SNAPSHOT` → `0.1.0`), following
   [semantic versioning](https://semver.org/). Commit and merge to `main` via a
   pull request titled `release: vX.Y.Z`.
2. **Publish a GitHub Release.** Go to
   [Releases → Draft a new release](https://github.com/iopsystems/h2histogram-java/releases/new):
   - **Choose a tag:** type `vX.Y.Z` (matching the `pom.xml` version) and select
     *"Create new tag on publish"*, targeting `main`.
   - **Title:** `vX.Y.Z`.
   - Click **Generate release notes**, then **Publish release**.
3. **Watch the workflow.** Publishing the release triggers `release.yml`, which
   verifies the tag matches the pom version, builds the jar plus sources and
   javadoc jars, signs everything with GPG, and uploads to Maven Central with
   `autoPublish` — no manual portal step. Follow it under the
   [Actions tab](https://github.com/iopsystems/h2histogram-java/actions/workflows/release.yml).
   Central validation takes a few minutes; sync to search/mirrors can take
   longer.
4. **Bump back to a snapshot.** Open a follow-up PR setting `<version>` to the
   next development version (e.g. `0.1.1-SNAPSHOT`).

> **Versions are immutable on Maven Central.** A published version can never be
> replaced or deleted. If you push a bad release, bump the version and cut a
> new one.

## One-time setup

### 1. Namespace verification

The `systems.iop` namespace (the reverse-DNS form of the `iop.systems`
domain) must be verified once in the
[Central portal](https://central.sonatype.com) (log in → **Namespaces** → add
`systems.iop`). Verification is via a DNS TXT record on `iop.systems`; the
portal shows the exact record to create.

### 2. Portal user token

In the portal: click your account → **View Account** → **Generate User Token**.
This produces a token *name* and *value* (these are not your portal login
credentials). Store them as repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|--------|-------|
| `CENTRAL_USERNAME` | the token name |
| `CENTRAL_PASSWORD` | the token value |

### 3. GPG signing key

Maven Central requires artifacts to be GPG-signed.

```bash
gpg --gen-key                          # if you don't already have a key; RSA, no expiry is fine
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish the public key
gpg --armor --export-secret-keys <KEY_ID>                   # private key, for the secret below
```

Store as repository secrets:

| Secret | Value |
|--------|-------|
| `GPG_PRIVATE_KEY` | the full ASCII-armored private key block |
| `GPG_PASSPHRASE` | the key's passphrase |

## Publishing manually (without CI)

With a verified namespace, a portal token in `~/.m2/settings.xml` (server id
`central`), and a local GPG key:

```bash
mvn -Prelease deploy
```
