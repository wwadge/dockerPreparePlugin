/*
 * Copyright (c) 2017 Gary Clayburg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.garyclayburg.docker

import groovy.util.logging.Slf4j
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Copy

/**
 * <br><br>
 * Created 2017-09-03 17:36
 *
 * @author Gary Clayburg
 */
@Slf4j
class DockerPreparePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.getLogger().info ' apply com.garyclayburg.dockerprepare'
        def settings = project.extensions.create("dockerprepare",DockerPreparePluginExt,project)
        settings.dockerSrcDirectory = "${project.rootDir}/src/main/docker"
        settings.dockerBuildDirectory = "${project.buildDir}/docker"
        boolean prereqsmet = true
        def bootRepackageTask
        try {
            bootRepackageTask = project.tasks.getByName('bootRepackage')
        } catch (UnknownTaskException ute) {
            project.getLogger().error('\'bootRepackage\' task does not exist so there is nothing to do.  Is spring boot installed correctly?', ute)
            prereqsmet = false
        }
        if (prereqsmet) {
            def jarTask = project.tasks.getByName('jar')
            project.task('copyDocker', type: Copy) {
                from { project.dockerprepare.dockerSrcDirectory} //lazy evaluation via closure so that dockerprepare settings can be overridden in build.properties
                into { project.dockerprepare.dockerBuildDirectory }
                doLast {
                    def mysrc = { project.dockerprepare.dockerSrcDirectory}
                    def myto = { project.dockerprepare.dockerBuildDirectory }
                    getLogger().info("Copying docker file(s) from ${mysrc()} to:\n${myto()}")
                }
            }.onlyIf {
                def file = project.file(settings.dockerSrcDirectory)
                (file.isDirectory() && (file.list().length != 0))
            }
            project.task('copyDefaultDockerfile') {
                outputs.files {[settings.dockerBuildDirectory +"/Dockerfile",settings.dockerBuildDirectory +"/bootrunner.sh"]}
                doLast {
                    def myjar = project.buildscript.configurations.classpath.find {
                        it.name.contains 'dockerPreparePlugin'
                    }
                    if (myjar != null) {
                        getLogger().info "Copy opinionated default Dockerfile and bootrunner.sh into ${settings.dockerBuildDirectory}"
                        project.copy {
                            from project.resources.text.fromArchiveEntry(myjar,
                                    '/defaultdocker/Dockerfile').asFile()
                            into settings.dockerBuildDirectory
                        }
                        project.copy {
                            from project.resources.text.fromArchiveEntry(myjar,
                                    '/defaultdocker/bootrunner.sh').asFile()
                            into settings.dockerBuildDirectory
                        }
                    } else {
                        getLogger().error('Cannot copy opinionated default Dockerfile and bootrunner.sh')
                        getLogger().info "classpath files " + project.buildscript.configurations.classpath.findAll {
                            true
                        }
                    }
                }
            }.onlyIf {
                def file = project.file(settings.dockerSrcDirectory)
                (!file.isDirectory() || (file.list().length == 0))
            }
            project.task('expandBootJar') {
                inputs.file {jarTask.archivePath}
                outputs.dir {settings.dockerBuildDependenciesDirectory}
                doLast {
                    getLogger().info("populating dependencies layer ${settings.dockerBuildDependenciesDirectory} from \n${jarTask.archivePath}")
                    //in some projects, jar.archivePath may change after bootRepackage is executed.
                    // It might be one value during configure, but another after bootRepackage.
                    project.copy {
                        from project.zipTree(jarTask.archivePath)
                        into settings.dockerBuildDependenciesDirectory
                        exclude "/BOOT-INF/classes/**"
                        exclude "/META-INF/**"
                    }
                    getLogger().info("populating classes layer ${settings.dockerBuildClassesDirectory} from \n${jarTask.archivePath}")
                    project.copy {
                        from project.zipTree(jarTask.archivePath)
                        into settings.dockerBuildClassesDirectory
                        include "/BOOT-INF/classes/**"
                        include "/META-INF/**"
                    }
                }
            }.setDependsOn([bootRepackageTask])
            def dockerPrep = project.task('dockerPrepare')
            dockerPrep.dependsOn('expandBootJar', 'copyDocker', 'copyDefaultDockerfile')

            project.getTasks().getByName(BasePlugin.ASSEMBLE_TASK_NAME).dependsOn('dockerPrepare')
        }
    }
}