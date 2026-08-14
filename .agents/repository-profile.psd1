@{
    Name = "YSM-EpicFight-Compat"
    ForbiddenTrackedPatterns = @(
        "(^|/)(build|run|run-server|logs)/"
        "^config/"
        "(^|/).+\.ysm`$"
        "^(?!gradle/wrapper/gradle-wrapper\.jar`$).+\.jar`$"
        "\.(dll|so|dylib)`$"
    )
    ValidationRepositories = @(
    )
    RepositoryVerifier = ".agents/skills/maintain-ysm-epicfight-integration/scripts/validate-integration.ps1"
    RepositoryVerifierProfiles = @(
        "Minecraft"
    )
    MainValidation = @(
    )
    MinecraftValidation = @(
    )
}
