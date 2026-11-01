pluginManagement {
    repositories {
        // 国内镜像加速。境外网络环境可删除这两个 mirror 块，直接走 google()/mavenCentral()。
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public") {
            name = "TencentMirror"
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 同上：境内镜像优先，境外可删。
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public") {
            name = "TencentMirror"
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ZcodeRemote"
include(":app")
