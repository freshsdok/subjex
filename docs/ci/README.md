# CI workflow waiting to be moved / 等待移到位的 CI 工作流

`build.yml` here is the GitHub Actions workflow that runs `mvn -B test` on every push and pull request.
It belongs at `.github/workflows/build.yml`. GitHub refused that path for the token used to push it, because writing workflow files needs the `workflow` scope. Someone with that scope moves it:

这里的 `build.yml` 是 GitHub Actions 工作流，每次 push 和 pull request 都跑 `mvn -B test`。
它应当放在 `.github/workflows/build.yml`。推送所用的令牌没有 `workflow` 权限，GitHub 拒绝了那个路径。有该权限的人把它移过去：

```shell
mkdir -p .github/workflows
git mv docs/ci/build.yml .github/workflows/build.yml
git rm docs/ci/README.md
git commit -m "Move the CI workflow into place"
```

Until then, CI does not run. / 在此之前 CI 不会运行。
