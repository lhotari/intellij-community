/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.debugger.engine;

import com.intellij.debugger.DebugEnvironment;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.DefaultDebugEnvironment;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.ProcessInfo;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.internal.DebugAttachDetector;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.attach.XDefaultLocalAttachGroup;
import com.intellij.xdebugger.attach.XLocalAttachDebugger;
import com.intellij.xdebugger.attach.XLocalAttachDebuggerProvider;
import com.intellij.xdebugger.attach.XLocalAttachGroup;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * @author egor
 */
public class JavaAttachDebuggerProvider implements XLocalAttachDebuggerProvider {
  private static final XLocalAttachDebugger ourAttachDebugger = new XLocalAttachDebugger() {
    @NotNull
    @Override
    public String getDebuggerDisplayName() {
      return "Java Debugger";
    }

    @Override
    public void attachDebugSession(@NotNull Project project, @NotNull ProcessInfo processInfo) throws ExecutionException {
      Pair<String, Integer> address = getAttachAddress(processInfo);
      assert address != null;

      // TODO: first need to remove circular dependency with execution-impl
      //RunnerAndConfigurationSettings runSettings = RunManager.getInstance(project)
      //  .createRunConfiguration(StringUtil.notNullize(address.first) + ":" + address.second,
      //                          RemoteConfigurationType.getInstance().getFactory());
      //
      //RemoteConfiguration configuration = (RemoteConfiguration)runSettings.getConfiguration();
      //configuration.HOST = address.first;
      //configuration.PORT = String.valueOf(address.second);
      //configuration.USE_SOCKET_TRANSPORT = true;
      //configuration.SERVER_MODE = false;
      //
      //ProgramRunnerUtil.executeConfiguration(project, runSettings, DefaultDebugExecutor.getDebugExecutorInstance());

      String name = getAttachString(address);
      RunnerAndConfigurationSettings runSettings = RunManager.getInstance(project)
        .createRunConfiguration(name, ConfigurationTypeUtil.findConfigurationType("Remote").getConfigurationFactories()[0]);

      RemoteConnection remoteConnection = new RemoteConnection(true, address.first, String.valueOf(address.second), false);
      ExecutionEnvironment env = new ExecutionEnvironmentBuilder(project, DefaultDebugExecutor.getDebugExecutorInstance())
        .runProfile(runSettings.getConfiguration())
        .build();
      DebugEnvironment environment = new DefaultDebugEnvironment(env, new RemoteStateState(project, remoteConnection), remoteConnection, 0);
      final DebuggerSession debuggerSession = DebuggerManagerEx.getInstanceEx(env.getProject()).attachVirtualMachine(environment);
      if (debuggerSession == null) {
        return;
      }

      XDebuggerManager.getInstance(project).startSessionAndShowTab(name, null, new XDebugProcessStarter() {
        @Override
        @NotNull
        public XDebugProcess start(@NotNull XDebugSession session) {
          return JavaDebugProcess.create(session, debuggerSession);
        }
      });
    }
  };

  private static String getAttachString(Pair<String, Integer> address) {
    return StringUtil.notNullize(address.first) + ":" + address.second;
  }

  private static final XLocalAttachGroup ourAttachGroup = new XDefaultLocalAttachGroup() {
    @Override
    public int getOrder() {
      return 1;
    }

    @NotNull
    @Override
    public String getGroupName() {
      return "Java";
    }

    @NotNull
    @Override
    public String getProcessDisplayText(@NotNull Project project, @NotNull ProcessInfo info, @NotNull UserDataHolder dataHolder) {
      Pair<String, Integer> address = getAttachAddress(info);
      assert address != null;
      return StringUtil.notNullize(ArrayUtil.getLastElement(info.getCommandLine().split(" "))) + " (" + getAttachString(address) + ')';
    }
  };

  @NotNull
  @Override
  public XLocalAttachGroup getAttachGroup() {
    return ourAttachGroup;
  }

  @NotNull
  @Override
  public List<XLocalAttachDebugger> getAvailableDebuggers(@NotNull Project project,
                                                          @NotNull ProcessInfo processInfo,
                                                          @NotNull UserDataHolder contextHolder) {
    Pair<String, Integer> address = getAttachAddress(processInfo);
    if (address != null) {
      return Collections.singletonList(ourAttachDebugger);
    }
    return Collections.emptyList();
  }

  private static Pair<String, Integer> getAttachAddress(ProcessInfo processInfo) {
    return DebugAttachDetector.getAttachAddress(StringUtil.split(processInfo.getCommandLine(), " "));
  }
}
