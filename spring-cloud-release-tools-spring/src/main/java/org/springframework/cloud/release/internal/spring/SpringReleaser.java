/*
 * Copyright 2013-2019 the original author or authors.
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

/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.release.internal.spring;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.release.internal.Releaser;
import org.springframework.cloud.release.internal.ReleaserProperties;
import org.springframework.cloud.release.internal.options.Options;
import org.springframework.cloud.release.internal.options.OptionsBuilder;
import org.springframework.cloud.release.internal.pom.ProjectVersion;
import org.springframework.cloud.release.internal.pom.Projects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.StringUtils;

/**
 * Releaser that gets input from console.
 *
 * @author Marcin Grzejszczak
 */
public class SpringReleaser {

	private static final Logger log = LoggerFactory.getLogger(SpringReleaser.class);

	private final Releaser releaser;

	private final ReleaserProperties properties;

	private final OptionsProcessor optionsProcessor;

	private final ReleaserPropertiesUpdater updater;

	private final ApplicationEventPublisher applicationEventPublisher;

	public SpringReleaser(Releaser releaser, ReleaserProperties properties,
			ReleaserPropertiesUpdater updater,
			ApplicationEventPublisher applicationEventPublisher) {
		this.releaser = releaser;
		this.properties = properties;
		this.updater = updater;
		this.applicationEventPublisher = applicationEventPublisher;
		this.optionsProcessor = new OptionsProcessor(releaser, properties,
				applicationEventPublisher);
	}

	SpringReleaser(Releaser releaser, ReleaserProperties properties,
			OptionsProcessor optionsProcessor, ReleaserPropertiesUpdater updater,
			ApplicationEventPublisher applicationEventPublisher) {
		this.releaser = releaser;
		this.properties = properties;
		this.optionsProcessor = optionsProcessor;
		this.updater = updater;
		this.applicationEventPublisher = applicationEventPublisher;
	}

	/**
	 * Default behaviour - interactive mode.
	 */
	public void release() {
		release(new OptionsBuilder().options());
	}

	public void release(Options options) {
		ProjectsAndVersion projectsAndVersion = null;
		if (options.metaRelease) {
			prepareForMetaRelease(options);
		}
		if (this.properties.isPostReleaseTasksOnly()) {
			log.info("Skipping release process and moving only to post release");
			this.optionsProcessor.postReleaseOptions(options,
					postReleaseOptionsAgs(options, projectsAndVersion));
			buildCompleted();
			return;
		}
		performReleaseAndPostRelease(options, projectsAndVersion);
		buildCompleted();
	}

	private void buildCompleted() {
		this.applicationEventPublisher.publishEvent(new BuildCompleted(this));
	}

	private void performReleaseAndPostRelease(Options options,
			ProjectsAndVersion projectsAndVersion) {
		if (options.metaRelease) {
			ReleaserProperties original = this.properties.copy();
			log.debug("The following properties were found [{}]", original);
			metaReleaseProjects(options)
					.forEach(project -> processProjectForMetaRelease(original.copy(),
							options, project));
		}
		else {
			log.info(
					"Single project release picked. Will release only the current project");
			File projectFolder = projectFolder();
			projectsAndVersion = processProject(options, projectFolder, TaskType.RELEASE);
		}
		this.optionsProcessor.postReleaseOptions(options,
				postReleaseOptionsAgs(options, projectsAndVersion));
	}

	private void prepareForMetaRelease(Options options) {
		log.info(
				"Meta Release picked. Will iterate over all projects and perform release of each one");
		this.properties.getGit().setFetchVersionsFromGit(false);
		this.properties.getMetaRelease().setEnabled(options.metaRelease);
	}

	void processProjectForMetaRelease(ReleaserProperties copy, Options options,
			String project) {
		log.info("Original properties [\n\n{}\n\n]", copy);
		File clonedProjectFromOrg = this.releaser.clonedProjectFromOrg(project);
		updatePropertiesIfCustomConfigPresent(copy, clonedProjectFromOrg);
		log.info("Successfully cloned the project [{}] to [{}]", project,
				clonedProjectFromOrg);
		try {
			processProject(options, clonedProjectFromOrg, TaskType.RELEASE);
		}
		catch (Exception e) {
			log.error("\n\n\nBUILD FAILED!!!\n\nException occurred for project <"
					+ project + "> \n\n", e);
			throw e;
		}
	}

	private ReleaserProperties updatePropertiesIfCustomConfigPresent(
			ReleaserProperties copy, File clonedProjectFromOrg) {
		return this.updater.updateProperties(copy, clonedProjectFromOrg);
	}

	List<String> metaReleaseProjects(Options options) {
		List<String> projects = new ArrayList<>(
				this.properties.getFixedVersions().keySet());
		log.info("List of projects that should not be cloned {}",
				this.properties.getMetaRelease().getProjectsToSkip());
		List<String> filteredProjects = projects
				.stream().filter(project -> !this.properties.getMetaRelease()
						.getProjectsToSkip().contains(project))
				.collect(Collectors.toList());
		log.info("List of all projects to clone before filtering {}", filteredProjects);
		if (StringUtils.hasText(options.startFrom)) {
			log.info("Start from option provided [{}]", options.startFrom);
			int projectIndex = filteredProjects.indexOf(options.startFrom);
			if (projectIndex < 0) {
				throw new IllegalStateException(
						"Project [" + options.startFrom + "] not found");
			}
			if (log.isDebugEnabled()) {
				log.debug("Index of project [{}] is [{}]", options.startFrom,
						projectIndex);
			}
			filteredProjects = filteredProjects.subList(projectIndex,
					filteredProjects.size());
			options.startFrom = "";
			enforceFullRelease(options);
		}
		else if (!options.taskNames.isEmpty()) {
			log.info("Task names provided {}", options.taskNames);
			filteredProjects = filteredProjects.stream()
					.filter(project -> options.taskNames.contains(project))
					.collect(Collectors.toList());
			options.taskNames = new ArrayList<>();
			enforceFullRelease(options);
		}
		log.info("\n\n\nFor meta-release, will release the projects {}\n\n\n",
				filteredProjects);
		return filteredProjects;
	}

	protected void enforceFullRelease(Options options) {
		options.fullRelease = true;
	}

	private File projectFolder() {
		String workingDir = this.properties.getWorkingDir();
		return new File(workingDir);
	}

	Args postReleaseOptionsAgs(Options options, ProjectsAndVersion projectsAndVersion) {
		Projects projects = projectsAndVersion == null
				? projectsToUpdateForFixedVersions() : projectsAndVersion.projectVersions;
		ProjectVersion version = projects.containsProject(
				this.properties.getMetaRelease().getReleaseTrainProjectName())
						? projects.releaseTrain(this.properties) : versionFromBranch();
		if (options.metaRelease) {
			this.properties.getPom().setBranch(version.version);
		}
		return new Args(this.releaser, projects, version, this.properties,
				options.interactive, this.applicationEventPublisher);
	}

	private ProjectVersion versionFromBranch() {
		String branch = this.properties.getPom().getBranch();
		return new ProjectVersion(projectFolder().getName(),
				branch.startsWith("v") ? branch.substring(1) : branch);
	}

	private ProjectsAndVersion projects(File project) {
		ProjectVersion versionFromScRelease;
		Projects projectsToUpdate;
		log.info("Fetch from git [{}], meta release [{}]",
				this.properties.getGit().isFetchVersionsFromGit(),
				this.properties.getMetaRelease().isEnabled());
		if (this.properties.getGit().isFetchVersionsFromGit()
				&& !this.properties.getMetaRelease().isEnabled()) {
			printVersionRetrieval();
			projectsToUpdate = this.releaser.retrieveVersionsFromSCRelease();
			versionFromScRelease = projectsToUpdate.forFile(project);
			assertNoSnapshotsForANonSnapshotProject(projectsToUpdate,
					versionFromScRelease);
		}
		else {
			ProjectVersion originalVersion = new ProjectVersion(project);
			String fixedVersionForProject = this.properties.getFixedVersions()
					.get(project.getName());
			versionFromScRelease = StringUtils.hasText(fixedVersionForProject)
					? new ProjectVersion(originalVersion.projectName,
							fixedVersionForProject)
					: new ProjectVersion(project);
			projectsToUpdate = this.properties.getFixedVersions().entrySet().stream()
					.map(entry -> new ProjectVersion(entry.getKey(), entry.getValue()))
					.collect(Collectors.toCollection(Projects::new));
			projectsToUpdate.add(versionFromScRelease);
			printSettingVersionFromFixedVersions(projectsToUpdate);
		}
		return new ProjectsAndVersion(projectsToUpdate, versionFromScRelease);
	}

	ProjectsAndVersion processProject(Options options, File project, TaskType taskType) {
		ProjectsAndVersion projectsAndVersion = projects(project);
		ProjectVersion originalVersion = new ProjectVersion(project);
		final Args defaultArgs = new Args(this.releaser, project,
				projectsAndVersion.projectVersions, originalVersion,
				projectsAndVersion.versionFromScRelease, this.properties,
				options.interactive, taskType, this.applicationEventPublisher);
		log.debug("Processing project [{}] with args [{}]", project, defaultArgs);
		this.optionsProcessor.processOptions(options, defaultArgs);
		return projectsAndVersion;
	}

	private Projects projectsToUpdateForFixedVersions() {
		Projects projectsToUpdate = this.releaser.fixedVersions();
		printSettingVersionFromFixedVersions(projectsToUpdate);
		return projectsToUpdate;
	}

	private void printVersionRetrieval() {
		log.info(
				"\n\n\n=== RETRIEVING VERSIONS ===\n\nWill clone Spring Cloud Release"
						+ " to retrieve all versions for the branch [{}]",
				this.properties.getPom().getBranch());
	}

	private void printSettingVersionFromFixedVersions(Projects projectsToUpdate) {
		log.info(
				"\n\n\n=== RETRIEVED VERSIONS ===\n\nWill use the fixed versions"
						+ " of projects\n\n{}",
				projectsToUpdate.stream().map(p -> p.projectName + " => " + p.version)
						.collect(Collectors.joining("\n")));
	}

	private void assertNoSnapshotsForANonSnapshotProject(Projects projects,
			ProjectVersion versionFromScRelease) {
		if (!versionFromScRelease.isSnapshot() && projects.containsSnapshots()) {
			throw new IllegalStateException("You are trying to release a non snapshot "
					+ "version [" + versionFromScRelease + "] of the project ["
					+ versionFromScRelease.projectName + "] but "
					+ "there is at least one SNAPSHOT library version in the Spring Cloud Release project");
		}
	}

	class ProjectsAndVersion {

		final Projects projectVersions;

		final ProjectVersion versionFromScRelease;

		ProjectsAndVersion(Projects projectVersions,
				ProjectVersion versionFromScRelease) {
			this.projectVersions = projectVersions;
			this.versionFromScRelease = versionFromScRelease;
		}

	}

}
