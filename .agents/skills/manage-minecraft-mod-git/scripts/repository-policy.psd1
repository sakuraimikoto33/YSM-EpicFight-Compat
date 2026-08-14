@{
    MainBranch = "main"
    MinecraftBranchPattern = "^mc/[0-9A-Za-z][0-9A-Za-z._+-]*$"
    ActiveMinecraftBranchesFile = ".agents/active-minecraft-branches.txt"
    ContractVersionPatterns = @(
        "^\+.*\bmodVersion\s*="
    )
    DependencyVersionPatterns = @(
        "^\+(?!\+).*\b(?:fabricLoomVersion|fabricLoaderVersion|modDevGradleVersion|neoForgeVersion|loaderVersion|loomVersion|gradleVersion)\s*="
        "^\+(?!\+).*distributionUrl\s*=.*gradle-[0-9]"
        "^\+(?!\+).*\b(?:fabric|forge|neoforge|loom|loader|gradle|junit|gson|netty)[A-Za-z0-9_.-]*\s*=\s*[`"']?[0-9]"
    )
}
