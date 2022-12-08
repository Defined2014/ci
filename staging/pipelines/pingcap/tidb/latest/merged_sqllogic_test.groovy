// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
// TODO: remove env GIT_BASE_BRANCH and GIT_MERGE_COMMIT
final GIT_BASE_BRANCH = 'master'
final GIT_MERGE_COMMIT = '5aa66cb5c20d9818736d082a3df4e74ef178fd08'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_sqllogic_test.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        // parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_MERGE_COMMIT ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', GIT_BASE_BRANCH, "", GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_sqllogic_test/rev-${BUILD_TAG}") {
                        container("golang") {
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        }
                    }
                }
                dir('tidb-test') {
                    cache(path: "./sqllogic_test", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh 'touch ws-${BUILD_TAG}'
                        sh 'cd sqllogic_test && ./build.sh'
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
                    }
                    axis {
                        name 'TEST_PATH'
                        values 'random/aggregates_n1', 'random/aggregates_n2', 'random/expr', 'random/select_n1', 'random/select_n2',
                            'select', 'random/groupby', 'index/between/1', 'index/between/10', 'index/between/100', 'index/between/1000',
                            'index/commute/10', 'index/commute/100', 'index/commute/1000_n1', 'index/commute/1000_n2',
                            'index/delete/1', 'index/delete/10', 'index/delete/100', 'index/delete/1000', 'index/delete/10000'
                            // 'index/in/10', 'index/in/1000_n1', 'index/in/1000_n2', 'index/orderby/10', 'index/orderby/100',
                            // 'index/orderby/1000_n1', 'index/orderby/1000_n2', 'index/orderby_nosort/10', 'index/orderby_nosort/100',
                            // 'index/orderby_nosort/1000_n1', 'index/orderby_nosort/1000_n2', 'index/random/10', 'index/random/100',
                            // 'index/random/1000'
                    }
                    axis {
                        name 'PARALLELISM'
                        values '8'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_sqllogic_test/rev-${BUILD_TAG}") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'   
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./sqllogic_test", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh """
                                        mkdir -p bin
                                        cp ${WORKSPACE}/tidb/bin/tidb-server sqllogic_test/
                                        ls -alh sqllogic_test/
                                    """
                                    container("golang") {
                                        sh label: "test_path: ${TEST_PATH}, cache_enabled:${CACHE_ENABLED}, parallelism: ${PARALLELISM}", script: """
                                            #!/usr/bin/env bash
                                            cd sqllogic_test/
                                            SQLLOGIC_TEST_PATH="/git/sqllogictest/test/${TEST_PATH}" \
                                            TIDB_PARALLELISM=${PARALLELISM} \
                                            TIDB_SERVER_PATH=`pwd`/tidb-server \
                                            CACHE_ENABLED=${CACHE_ENABLED} \
                                            ./test.sh
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }        
        }
    }
}