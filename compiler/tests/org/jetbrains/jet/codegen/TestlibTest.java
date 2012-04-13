/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.codegen;

import com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import gnu.trove.THashSet;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.jetbrains.jet.CompileCompilerDependenciesTest;
import org.jetbrains.jet.codegen.forTestCompile.ForTestCompileRuntime;
import org.jetbrains.jet.compiler.KotlinToJVMBytecodeCompiler;
import org.jetbrains.jet.compiler.MessageRenderer;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.psi.JetClass;
import org.jetbrains.jet.lang.psi.JetDeclaration;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.java.CompilerDependencies;
import org.jetbrains.jet.lang.resolve.java.CompilerSpecialMode;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.parsing.JetParsingTest;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * @author alex.tkachman
 */
public class TestlibTest extends CodegenTestCase {

    public static Test suite() {
        return new TestlibTest().buildSuite();
    }

    protected TestSuite buildSuite() {
        try {
            setUp();
            return doBuildSuite();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        finally {
            try {
                tearDown();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private TestSuite doBuildSuite() {
        try {
            PrintStream err = System.err;
            CompilerDependencies compilerDependencies = CompileCompilerDependenciesTest.compilerDependenciesForTests(CompilerSpecialMode.REGULAR);
            File junitJar = new File("libraries/lib/junit-4.9.jar");

            if (!junitJar.exists()) {
                throw new AssertionError();
            }

            myEnvironment.addToClasspath(junitJar);

            myEnvironment.addToClasspath(compilerDependencies.getRuntimeJar());

            CoreLocalFileSystem localFileSystem = myEnvironment.getLocalFileSystem();
            myEnvironment.addSources(localFileSystem.findFileByPath(JetParsingTest.getTestDataDir() + "/../../libraries/stdlib/test"));
            myEnvironment.addSources(localFileSystem.findFileByPath(JetParsingTest.getTestDataDir() + "/../../libraries/kunit/src"));

            GenerationState generationState = KotlinToJVMBytecodeCompiler
                    .analyzeAndGenerate(myEnvironment, compilerDependencies, MessageRenderer.PLAIN, err, false, false);

            if (generationState == null) {
                throw new RuntimeException("There were compilation errors");
            }

            ClassFileFactory classFileFactory = generationState.getFactory();

            final GeneratedClassLoader loader = new GeneratedClassLoader(
                    classFileFactory,
                    new URLClassLoader(new URL[]{ForTestCompileRuntime.runtimeJarForTests().toURI().toURL(), junitJar.toURI().toURL()},
                                       TestCase.class.getClassLoader()));

            JetTypeMapper typeMapper = generationState.getInjector().getJetTypeMapper();
            TestSuite suite = new TestSuite("stdlib_test");
            try {
                for(JetFile jetFile : myEnvironment.getSourceFiles()) {
                    for(JetDeclaration decl : jetFile.getDeclarations()) {
                        if(decl instanceof JetClass) {
                            JetClass jetClass = (JetClass) decl;

                            ClassDescriptor descriptor = (ClassDescriptor) generationState.getBindingContext().get(BindingContext.DECLARATION_TO_DESCRIPTOR, jetClass);
                            Set<JetType> allSuperTypes = new THashSet<JetType>();
                            DescriptorUtils.addSuperTypes(descriptor.getDefaultType(), allSuperTypes);

                            for(JetType type : allSuperTypes) {
                                String internalName = typeMapper.mapType(type, MapTypeMode.IMPL).getInternalName();
                                if(internalName.equals("junit/framework/Test")) {
                                    String name = typeMapper.mapType(descriptor.getDefaultType(), MapTypeMode.IMPL).getInternalName();
                                    System.out.println(name);
                                    Class<TestCase> aClass = (Class<TestCase>) loader.loadClass(name.replace('/', '.'));
                                    if((aClass.getModifiers() & Modifier.ABSTRACT) == 0
                                     && (aClass.getModifiers() & Modifier.PUBLIC) != 0) {
                                        try {
                                            Constructor<TestCase> constructor = aClass.getConstructor();
                                            if(constructor != null && (constructor.getModifiers() & Modifier.PUBLIC) != 0) {
                                                suite.addTestSuite(aClass);
                                            }
                                        }
                                        catch (NoSuchMethodException e) {
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            finally {
                typeMapper = null;
            }

            return suite;

        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public void setUp() throws Exception {
        super.setUp();
        createEnvironmentWithFullJdk();
    }
}
