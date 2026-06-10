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

                base_branch="%mergeQueue.baseBranch%"
                properties_file="%system.teamcity.configuration.properties.file%"
                queue_prefix="gh-readonly-queue/${'$'}{base_branch}/"

                # Optional PR parameters are read from the properties file to avoid
                # undefined-parameter warnings on merge queue builds.
                read_teamcity_property() {
                    local key="${'$'}1"
                    local file="${'$'}2"

                    [[ -f "${'$'}file" ]] || return 1

                    awk -v key="${'$'}key" '
                        BEGIN { equals = key "="; colon = key ":"; found = 0 }
                        index(${'$'}0, equals) == 1 {
                            print substr(${'$'}0, length(equals) + 1)
                            found = 1
                            exit
                        }
                        index(${'$'}0, colon) == 1 {
                            print substr(${'$'}0, length(colon) + 1)
                            found = 1
                            exit
                        }
                        END { if (!found) exit 1 }
                    ' "${'$'}file"
                }

                get_pull_request_number() {
                    read_teamcity_property "teamcity.pullRequest.number" "${'$'}properties_file" || true
                }

                is_merge_queue_branch() {
                    local candidate="${'$'}{1:-}"
                    [[ "${'$'}candidate" == "${'$'}queue_prefix"* || "${'$'}candidate" == "refs/heads/${'$'}queue_prefix"* ]]
                }

                normalize_git_ref() {
                    local ref="${'$'}1"

                    ref="${'$'}{ref#refs/heads/}"
                    ref="${'$'}{ref#refs/remotes/}"
                    ref="${'$'}{ref#origin/}"

                    printf '%s\n' "${'$'}ref"
                }

                get_teamcity_branch_name() {
                    local configured_branch="%teamcity.build.branch%"

                    if [[ "${'$'}configured_branch" == "%teamcity.build.branch%" || -z "${'$'}configured_branch" ]]; then
                        configured_branch="${'$'}{TEAMCITY_BUILD_BRANCH:-}"
                    fi

                    printf '%s\n' "${'$'}configured_branch"
                }

                get_git_branch_name_at_head() {
                    command -v git >/dev/null || return 1
                    git rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 1

                    local fallback=""

                    while IFS= read -r ref; do
                        local normalized
                        normalized="$(normalize_git_ref "${'$'}ref")"

                        if is_merge_queue_branch "${'$'}normalized"; then
                            printf '%s\n' "${'$'}normalized"
                            return 0
                        fi

                        if [[ -z "${'$'}fallback" ]]; then
                            fallback="${'$'}normalized"
                        fi
                    done < <(git for-each-ref --format='%(refname)' --points-at HEAD refs/heads refs/remotes 2>/dev/null || true)

                    [[ -n "${'$'}fallback" ]] || return 1
                    printf '%s\n' "${'$'}fallback"
                }

                get_branch_name() {
                    local teamcity_branch
                    teamcity_branch="$(get_teamcity_branch_name)"

                    if is_merge_queue_branch "${'$'}teamcity_branch"; then
                        printf '%s\n' "${'$'}teamcity_branch"
                        return 0
                    fi

                    local git_branch
                    git_branch="$(get_git_branch_name_at_head || true)"

                    if is_merge_queue_branch "${'$'}git_branch"; then
                        printf '%s\n' "${'$'}git_branch"
                        return 0
                    fi

                    if [[ -n "${'$'}teamcity_branch" ]]; then
                        printf '%s\n' "${'$'}teamcity_branch"
                        return 0
                    fi

                    [[ -n "${'$'}git_branch" ]] || return 1
                    printf '%s\n' "${'$'}git_branch"
                }

                branch="$(get_branch_name || true)"
                pull_request_number="$(get_pull_request_number)"

                if is_merge_queue_branch "${'$'}branch"; then
                    echo "GitHub merge queue branch detected (${'$'}branch); running required checks."
                elif [[ -n "${'$'}pull_request_number" ]]; then
                    echo "Pull request #${'$'}pull_request_number; passing early."
                    exit 0
                elif [[ -n "${'$'}branch" ]]; then
                    echo "Not a GitHub merge queue branch (${'$'}branch); passing early."
                    exit 0
                else
                    echo "Could not determine whether this is a pull request or merge queue build; refusing to run checks ambiguously."
                    exit 1
                fi

                grep -qw "pass" README.md
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
