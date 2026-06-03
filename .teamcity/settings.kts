import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.PullRequests
import jetbrains.buildServer.configs.kotlin.buildFeatures.commitStatusPublisher
import jetbrains.buildServer.configs.kotlin.buildFeatures.pullRequests
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.vcs

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2025.11"

project {
    buildType(MergeQueueRequiredCheck)
}

object MergeQueueRequiredCheck : BuildType({
    id("MergeQueueRequiredCheck")
    name = "Merge Queue Required Check"
    description = "Required GitHub check that passes quickly on PRs and runs validation on merge queue branches."

    params {
        param("mergeQueue.baseBranch", "trunk")
    }

    vcs {
        // The settings VCS root branch specification must include:
        // +:refs/heads/(gh-readonly-queue/trunk/*)
        root(DslContext.settingsRoot)
    }

    steps {
        script {
            name = "Gate merge queue checks"
            scriptContent = """
                #!/usr/bin/env bash
                set -euo pipefail

                branch="%teamcity.build.branch%"
                base_branch="%mergeQueue.baseBranch%"
                queue_prefix="gh-readonly-queue/${'$'}{base_branch}/"

                if [[ "${'$'}branch" == "%teamcity.build.branch%" || -z "${'$'}branch" ]]; then
                    branch="${'$'}{TEAMCITY_BUILD_BRANCH:-}"
                fi

                if [[ "${'$'}branch" != "${'$'}queue_prefix"* && "${'$'}branch" != "refs/heads/${'$'}queue_prefix"* ]]; then
                    echo "Not a GitHub merge queue branch (${'$'}{branch:-unknown}); passing early."
                    exit 0
                fi

                echo "GitHub merge queue branch detected (${'$'}branch); running required checks."
                test -f README.md
            """.trimIndent()
        }
    }

    triggers {
        vcs {
            branchFilter = """
                -:*
                +:gh-readonly-queue/%mergeQueue.baseBranch%/*
                +:refs/heads/gh-readonly-queue/%mergeQueue.baseBranch%/*
                +pr:target=%mergeQueue.baseBranch%
            """.trimIndent()
        }
    }

    features {
        pullRequests {
            provider = github {
                authType = vcsRoot()
                filterTargetBranch = "refs/heads/%mergeQueue.baseBranch%"
                filterAuthorRole = PullRequests.GitHubRoleFilter.EVERYBODY
            }
        }

        commitStatusPublisher {
            publisher = github {
                githubUrl = "https://api.github.com"
                authType = vcsRoot()
            }
        }
    }
})
