@{
    Name = "YSM-EpicFight-Compat"
    MainBranch = "main"
    SharedPaths = @(
        ".agents"
        "AGENTS.md"
        ".gitignore"
        "LICENSE"
    )
    VersionPaths = @(
        "src"
        "docs"
        "gradle"
        "gradlew"
        "gradlew.bat"
        "build.gradle"
        "settings.gradle"
        "gradle.properties"
    )
    MixedPaths = @(
        "README.md"
        "README.ja.md"
    )
    ForbiddenTrackedPatterns = @(
        "(^|/)(build|run|run-server|logs)/"
        "(^|/)config/"
        "(^|/).+\.ysm$"
        "^(?!gradle/wrapper/gradle-wrapper\.jar$).+\.jar$"
        "\.(dll|so|dylib)$"
    )
    RepositoryVerifier = ".agents/skills/maintain-ysm-epicfight-integration/scripts/validate-integration.ps1"
}
