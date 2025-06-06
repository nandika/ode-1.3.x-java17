/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File

def ant = new AntBuilder()


def warname = project.build.directory+"/"+project.build.finalName+".war";
def webapp = project.build.directory + "/webapp";
def testresources = new File(project.basedir, 'src/test/webapp')
//def mavenresources = new File(project.basedir, 'src/main/webapp')

def hibconf = webapp + "/WEB-INF/conf.hib-derby";
def defconf = webapp + "/WEB-INF/conf";
def modules = webapp + "/WEB-INF/modules";

log.info('-----Performing pre-integration test tasks-----');
log.info('extracting the axis2 war');
ant.unwar(src: warname , dest: webapp )
log.info('copying over the test webapp resources');
ant.copy(todir: webapp) {
    fileset(dir: testresources) {
        include(name: '**')
    }
}

log.info('copying some maven specific resources')

//ant.copy(todir:defconf, overwrite:true) {
//    fileset(dir: mavenresources) {
//        include(name: 'ode-axis2.properties')
//    }
//}
//
//ant.copy(todir:hibconf, overwrite:true) {
//    fileset(dir: mavenresources) {
//        include(name: 'ode-axis2.properties')
//    }
//}
//
//ant.copy(todir:webapp + "/WEB-INF/modules") {
//    fileset(dir: mavenresources) {
//        include(name: 'modules.list')
//    }
//}

//This was in buildr, don't know if it is needed
//copy target/test-classes/TestEndpointProperties/*_global_conf*.endpoint to webapp/WEB-INF/conf

prepare_secure_services_tests (new File(project.build.testOutputDirectory,'TestRampartBasic/secured-services'),~/sample\d+\.axis2/, modules);
prepare_secure_services_tests (new File(project.build.testOutputDirectory,'TestRampartPolicy/secured-services'),~/sample\d+\-policy\.xml/, modules);
prepare_secure_processes_tests (new File(project.build.testOutputDirectory,'TestRampartBasic/secured-processes'), modules);
prepare_secure_processes_tests (new File(project.build.testOutputDirectory,'TestRampartPolicy/secured-processes'), modules);

def prepare_secure_processes_tests(test_dir, modules) {
    if (!test_dir.exists()) {
        log.info("Skipping secure process test setup — directory does not exist: ${test_dir}")
        return
    }
    ant.copy(todir: test_dir.getAbsolutePath()+ "/modules") {
        fileset(dir: modules) {
            include(name: '**')
        }
    }
    log.info('preparing the secure process tests in ' + test_dir);
    def p = ~/sample\d+\-service\.xml/;
    test_dir.eachFileMatch(p) { service_file ->
        def sample_name = service_file.getName().split('-')[0];
        def proc_dir = test_dir.getAbsolutePath() +'/process-' + sample_name;
        ant.copy(todir: proc_dir) {
            fileset(dir: test_dir.getAbsolutePath()+'/process-template') {
                include(name: '**')
            }
        }
        ant.copy(file: service_file, tofile:  proc_dir + '/HelloService.axis2');
    }
}

def prepare_secure_services_tests(test_dir, file_pattern, modules){
    if (!test_dir.exists()) {
        log.info("Skipping secure process test setup — directory does not exist: ${test_dir}")
        return
    }
    ant.copy(todir: test_dir.getAbsolutePath()+ "/modules") {
        fileset(dir: modules) {
            include(name: '**')
        }
    }
    log.info('preparing the secure services tests with pattern '+file_pattern+' in ' + test_dir);
    test_dir.eachFileMatch(file_pattern) { config_file ->
        def  sample_name =  config_file.getName().split("\\.")[0];
        def proc_dir = test_dir.getAbsolutePath() +'/process-' + sample_name;
        ant.filterset(id: sample_name+'_filter', begintoken: '{', endtoken: '}'){
            filter(token: 'sample.namespace', value: 'http://'+ sample_name.replaceAll('-','.')+'.samples.rampart.apache.org');
            filter(token: 'sample.service.name', value: sample_name);
            }
            ant.copy(todir: proc_dir) {
                fileset(dir: test_dir.getAbsolutePath()+'/process-template') {
                    include(name: '**')
                }
                filterset(refid: sample_name+'_filter');
            }
            ant.copy(todir: proc_dir, file: config_file){
                filterset(refid: sample_name+'_filter');
            }
        }
    }
