/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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
 */

package com.google.cloud.tools.intellij.appengine.facet.flexible;

import com.google.cloud.tools.intellij.appengine.cloud.AppEngineCloudType;
import com.google.cloud.tools.intellij.appengine.cloud.AppEngineDeploymentConfiguration;
import com.google.cloud.tools.intellij.appengine.cloud.AppEngineServerConfiguration;
import com.google.cloud.tools.intellij.appengine.cloud.CloudSdkAppEngineHelper;
import com.google.cloud.tools.intellij.appengine.cloud.flexible.AppEngineFlexibleDeploymentArtifactType;
import com.google.cloud.tools.intellij.appengine.facet.standard.AppEngineStandardFacet;
import com.google.cloud.tools.intellij.appengine.project.AppEngineProjectService;
import com.google.cloud.tools.intellij.appengine.project.AppEngineProjectService.FlexibleRuntime;
import com.google.cloud.tools.intellij.appengine.sdk.CloudSdkPanel;
import com.google.cloud.tools.intellij.appengine.sdk.CloudSdkService;
import com.google.cloud.tools.intellij.appengine.sdk.CloudSdkValidationResult;
import com.google.cloud.tools.intellij.ui.GoogleCloudToolsIcons;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.facet.FacetManager;
import com.intellij.facet.FacetType;
import com.intellij.framework.FrameworkTypeEx;
import com.intellij.framework.addSupport.FrameworkSupportInModuleConfigurable;
import com.intellij.framework.addSupport.FrameworkSupportInModuleProvider;
import com.intellij.ide.util.frameworkSupport.FrameworkSupportModel;
import com.intellij.openapi.module.JavaModuleType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.roots.ModifiableModelsProvider;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ui.configuration.FacetsProvider;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.remoteServer.ServerType;
import com.intellij.remoteServer.configuration.RemoteServer;
import com.intellij.remoteServer.configuration.RemoteServersManager;
import com.intellij.remoteServer.impl.configuration.deployment.DeployToServerConfigurationType;
import com.intellij.remoteServer.impl.configuration.deployment.DeployToServerConfigurationTypesRegistrar;
import com.intellij.remoteServer.impl.configuration.deployment.DeployToServerRunConfiguration;
import com.intellij.util.containers.ContainerUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Adds Flexible support to new or existing IJ modules.
 */
public class AppEngineFlexibleSupportProvider extends FrameworkSupportInModuleProvider {

  @NotNull
  @Override
  public FrameworkTypeEx getFrameworkType() {
    return AppEngineFlexibleFrameworkType.getFrameworkType();
  }

  @NotNull
  @Override
  public FrameworkSupportInModuleConfigurable createConfigurable(
      @NotNull FrameworkSupportModel model) {
    return new AppEngineFlexibleSupportConfigurable();
  }

  @Override
  public boolean isEnabledForModuleType(@NotNull ModuleType moduleType) {
    return moduleType instanceof JavaModuleType;
  }

  @Override
  public boolean isSupportAlreadyAdded(@NotNull Module module,
      @NotNull FacetsProvider facetsProvider) {
    return !facetsProvider.getFacetsByType(module, AppEngineFlexibleFacetType.ID).isEmpty()
        || !facetsProvider.getFacetsByType(module, AppEngineStandardFacet.ID).isEmpty();
  }

  static class AppEngineFlexibleSupportConfigurable extends FrameworkSupportInModuleConfigurable {

    private JPanel mainPanel;
    private CloudSdkPanel cloudSdkPanel;
    private JComboBox<FlexibleRuntime> runtimeComboBox;

    AppEngineFlexibleSupportConfigurable() {
      runtimeComboBox.setModel(new DefaultComboBoxModel<>(FlexibleRuntime.values()));
      runtimeComboBox.setSelectedItem(FlexibleRuntime.java);
    }

    @Nullable
    @Override
    public JComponent createComponent() {
      return mainPanel;
    }

    @Override
    public void addSupport(@NotNull Module module, @NotNull ModifiableRootModel rootModel,
        @NotNull ModifiableModelsProvider modifiableModelsProvider) {
      FacetType<AppEngineFlexibleFacet, AppEngineFlexibleFacetConfiguration> facetType =
          AppEngineFlexibleFacet.getFacetType();
      AppEngineFlexibleFacet facet = FacetManager.getInstance(module).addFacet(
          facetType, facetType.getPresentableName(), null /* underlyingFacet */);

      // Check if an app.yaml file already exists in the default location.
      VirtualFile[] contentRoots = rootModel.getContentRoots();
      AppEngineProjectService appEngineProjectService = AppEngineProjectService.getInstance();

      if (contentRoots.length > 0) {
        Path appYamlPath = Paths.get(
            appEngineProjectService.getDefaultAppYamlPath(contentRoots[0].getPath()));
        Path dockerfilePath = Paths.get(
            appEngineProjectService.getDefaultDockerfilePath(contentRoots[0].getPath()));

        generateConfigurationFiles(appYamlPath, dockerfilePath,
            new CloudSdkAppEngineHelper(module.getProject()));

        // Allows suggesting app.yaml and Dockerfile locations in facet and deployment UIs.
        facet.getConfiguration().setAppYamlPath(appYamlPath.toString());
        facet.getConfiguration().setDockerfilePath(dockerfilePath.toString());
      }

      // TODO(joaomartins): Add other run configurations here too.
      // https://github.com/GoogleCloudPlatform/google-cloud-intellij/issues/1260
      setupDeploymentRunConfiguration(module);

      CloudSdkService sdkService = CloudSdkService.getInstance();
      if (!sdkService.validateCloudSdk(cloudSdkPanel.getCloudSdkDirectoryText())
          .contains(CloudSdkValidationResult.MALFORMED_PATH)) {
        sdkService.setSdkHomePath(cloudSdkPanel.getCloudSdkDirectoryText());
      }
    }

    private void setupDeploymentRunConfiguration(Module module) {
      RunManager runManager = RunManager.getInstance(module.getProject());
      AppEngineCloudType serverType =
          ServerType.EP_NAME.findExtension(AppEngineCloudType.class);
      DeployToServerConfigurationType configurationType
          = DeployToServerConfigurationTypesRegistrar.getDeployConfigurationType(serverType);

      RunnerAndConfigurationSettings settings = runManager.createRunConfiguration(
          configurationType.getDisplayName(), configurationType.getFactory());

      // Sets the GAE Flex server, if any exists, in the run config.
      DeployToServerRunConfiguration<?, AppEngineDeploymentConfiguration> runConfiguration =
          (DeployToServerRunConfiguration<?, AppEngineDeploymentConfiguration>)
              settings.getConfiguration();
      RemoteServer<AppEngineServerConfiguration> server =
          ContainerUtil.getFirstItem(RemoteServersManager.getInstance().getServers(serverType));
      if (server != null) {
        runConfiguration.setServerName(server.getName());
      }

      runManager.addConfiguration(settings, false /* shared */);
    }

    /**
     * Asks the user if new configuration files should be generated and generates them if so.
     */
    private void generateConfigurationFiles(Path defaultAppYamlPath, Path defaultDockerfilePath,
        CloudSdkAppEngineHelper helper) {
      AppEngineProjectService appEngineProjectService = AppEngineProjectService.getInstance();
      // If app.yaml exists, ask user if they want to reuse files or generate new ones.
      if (Files.exists(defaultAppYamlPath)) {
        Optional<FlexibleRuntime> optionalRuntime =
            appEngineProjectService.getFlexibleRuntimeFromAppYaml(defaultAppYamlPath.toString());

        if (optionalRuntime.isPresent()) {
          int choice = Messages.showYesNoDialog(
              "An app.yaml with runtime " + optionalRuntime.get().name() + " already exists at "
                  + "the default app.yaml location. Do you want to keep the existing files "
                  + "or generate new ones for runtime "
                  + ((FlexibleRuntime) runtimeComboBox.getSelectedItem()).name() + "?",
              "app.yaml file already exists",
              "Keep them",
              "Generate new ones",
              GoogleCloudToolsIcons.APP_ENGINE);

          if (choice == Messages.YES) {
            // Don't generate files.
            return;
          }

          // Create backups of the files.
          try {
            Files.move(defaultAppYamlPath,
                defaultAppYamlPath.getParent().resolve(
                    defaultAppYamlPath.getFileName().toString() + ".bak"));
            Files.move(defaultDockerfilePath,
                defaultDockerfilePath.getParent().resolve(
                    defaultDockerfilePath.getFileName().toString() + ".bak"));
          } catch (IOException e) {
            // Do nothing for now.
          }
        }
      }

      // Generate files.
      helper
          .defaultAppYaml((FlexibleRuntime) runtimeComboBox.getSelectedItem())
          .ifPresent(
              appYaml -> {
                try {
                  FileUtil.copy(appYaml.toFile(), defaultAppYamlPath.toFile());
                } catch (IOException ioe) {
                  // Do nothing for now.
                }
              });
      if (runtimeComboBox.getSelectedItem() == FlexibleRuntime.custom) {
        helper.defaultDockerfile(AppEngineFlexibleDeploymentArtifactType.WAR)
            .ifPresent(dockerfile -> {
              try {
                FileUtil.copy(dockerfile.toFile(), defaultDockerfilePath.toFile());
              } catch (IOException ioe) {
                // Do nothing for now.
              }
            });
      }
    }

    private void createUIComponents() {
      cloudSdkPanel = new CloudSdkPanel();
      cloudSdkPanel.reset();
    }
  }
}
