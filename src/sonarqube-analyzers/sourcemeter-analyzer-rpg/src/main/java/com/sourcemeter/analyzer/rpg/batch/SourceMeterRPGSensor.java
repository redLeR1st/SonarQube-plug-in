/**
 * Copyright (c) 2014-2017, FrontEndART Software Ltd.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. All advertising materials mentioning features or use of this software
 *    must display the following acknowledgement:
 *    This product includes software developed by FrontEndART Software Ltd.
 * 4. Neither the name of FrontEndART Software Ltd. nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY FrontEndART Software Ltd. ''AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL FrontEndART Software Ltd. BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.sourcemeter.analyzer.rpg.batch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.bootstrap.ProjectDefinition;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputModule;
import org.sonar.api.batch.rule.Rules;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Settings;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.scan.filesystem.FileExclusions;
import org.sonar.api.server.rule.RulesDefinitionXmlLoader;

import com.sourcemeter.analyzer.base.batch.MetricHunterCategory;
import com.sourcemeter.analyzer.base.batch.ProfileInitializer;
import com.sourcemeter.analyzer.base.batch.SourceMeterSensor;
import com.sourcemeter.analyzer.base.helper.FileHelper;
import com.sourcemeter.analyzer.base.helper.GraphHelper;
import com.sourcemeter.analyzer.base.helper.ThresholdPropertiesHelper;
import com.sourcemeter.analyzer.base.visitor.NodeCounterVisitor;
import com.sourcemeter.analyzer.rpg.SourceMeterRPGMetrics;
import com.sourcemeter.analyzer.rpg.core.RPG;
import com.sourcemeter.analyzer.rpg.profile.SourceMeterRPGRuleRepository;
import com.sourcemeter.analyzer.rpg.visitor.CloneTreeSaverVisitorRPG;
import com.sourcemeter.analyzer.rpg.visitor.LogicalTreeLoaderVisitorRPG;
import com.sourcemeter.analyzer.rpg.visitor.LogicalTreeSaverVisitorRPG;
import com.sourcemeter.analyzer.rpg.visitor.PhysicalTreeLoaderVisitorRPG;

import graphlib.Graph;
import graphlib.GraphlibException;
import graphlib.Node;
import graphlib.Node.NodeType;
import graphlib.VisitorException;

import static com.sourcemeter.analyzer.rpg.SourceMeterRPGMetrics.SM_RPG_CLONE_TREE;
import static com.sourcemeter.analyzer.rpg.SourceMeterRPGMetrics.SM_RPG_LOGICAL_LEVEL1;
import static com.sourcemeter.analyzer.rpg.SourceMeterRPGMetrics.SM_RPG_LOGICAL_LEVEL2;
import static com.sourcemeter.analyzer.rpg.SourceMeterRPGMetrics.SM_RPG_LOGICAL_LEVEL3;

public class SourceMeterRPGSensor extends SourceMeterSensor {

    /**
     * Command and parameters for running SourceMeter RPG analyzer
     */
    private final List<String> commands;
    private final Rules rules;
    private final FileSystem fileSystem;

    private String projectName;
    private String resultsDir;

    private static final Logger LOG = LoggerFactory.getLogger(SourceMeterRPGSensor.class);
    private static final String THRESHOLD_PROPERTIES_PATH = "/threshold_properties.xml";
    private static final String LOGICAL_ROOT = "__LogicalRoot__";

    public SourceMeterRPGSensor(FileExclusions fileExclusions, FileSystem fileSystem,
            ProjectDefinition projectDefinition, Rules rules, RulesProfile profile,
            Settings settings) {

        super(fileExclusions, fileSystem, projectDefinition, profile, settings);

        this.commands = new ArrayList<String>();
        this.rules = rules;
        this.fileSystem = fileSystem;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(SensorContext sensorContext) {
        boolean skipRpg = this.settings.getBoolean("sm.rpg.skipToolchain");
        if (skipRpg) {
            LOG.info("SourceMeter toolchain is skipped for RPG. Results will be uploaded from former results directory, if it exists.");
        } else {
            if (!checkProperties()) {
                throw new RuntimeException("Failed to initialize the SourceMeter plugin. Some mandatory properties are not set properly.");
            }
            runSourceMeter(commands);
        }

        String analyseMode = this.settings.getString("sonar.analysis.mode");
        if ("incremental".equals(analyseMode)) {
            LOG.warn("Incremental mode is on. There are no metric based (INFO level) issues in this mode.");
            this.isIncrementalMode = true;
        }

        try {
            this.resultGraph = FileHelper.getSMSourcePath(settings, fileSystem, '_')
                    + File.separator + this.projectName + ".graph";
        } catch (IOException e) {
            LOG.error("Error during loading result graph path!", e);
        }

        long startTime = System.currentTimeMillis();
        LOG.info("      Graph: " + resultGraph);

        try {
            loadDataFromGraphBin(this.resultGraph, sensorContext.module(), sensorContext);
        } catch (GraphlibException e) {
            LOG.error("Error during graph loading!", e);
        }

        LOG.info("    Load data from graph bin and save resources and metrics done: " + (System.currentTimeMillis() - startTime) + MS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    /**
     * Collects the license information from result graph's header in a list,
     * and saves it to a special metric.
     *
     * @param graph Result graph.
     * @param sensorContext Context of the sensor.
     */
    private void saveLicense(Graph graph, SensorContext sensorContext) {
        Map<String, String> headerLicenseInformations = new HashMap<String, String>();
        headerLicenseInformations.put("FaultHunterRPG", "FaultHunter");
        headerLicenseInformations.put("MetricHunter", "MetricHunter");
        headerLicenseInformations.put("DuplicatedCodeFinder", "Duplicated Code");
        headerLicenseInformations.put("RPG2Metrics", "Metrics");

        super.saveLicense(graph, sensorContext, headerLicenseInformations, SourceMeterRPGMetrics.RPG_LICENSE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadDataFromGraphBin(String filename, InputModule project,
            SensorContext sensorContext) throws GraphlibException {
        Graph graph = new Graph();
        graph.loadBinary(filename);

        saveLicense(graph, sensorContext);

        Node componentRoot = null;
        NodeCounterVisitor nodeCounter = null;

        List<Node> components = graph.findNodes(new NodeType("System"));
        for (Node component : components) {
            String name = GraphHelper.getNodeNameAttribute(component);
            if (name != null && name.equals("System")) {
                componentRoot = component;
                break;
            }
        }

        try {
            LOG.info("      * Initialization...");
            long startTime = System.currentTimeMillis();

            if (componentRoot != null) {
                nodeCounter = new NodeCounterVisitor();
                GraphHelper.processGraph(graph, componentRoot, "ComponentTree", nodeCounter);
            }

            nodeCounter = new NodeCounterVisitor();
            GraphHelper.processGraph(graph, LOGICAL_ROOT, "LogicalTree", nodeCounter);
            LogicalTreeLoaderVisitorRPG logicalVisitor = new LogicalTreeLoaderVisitorRPG(
                    this.fileSystem, this.settings, sensorContext,
                    nodeCounter.getNumberOfNodes());

            nodeCounter = new NodeCounterVisitor();
            GraphHelper.processGraph(graph, "__PhysicalRoot__", "PhysicalTree", nodeCounter);
            PhysicalTreeLoaderVisitorRPG physicalVisitor = new PhysicalTreeLoaderVisitorRPG(
                    this.fileSystem, sensorContext, nodeCounter.getNumberOfNodes());

            nodeCounter = new NodeCounterVisitor();
            GraphHelper.processGraph(graph, LOGICAL_ROOT, "logicalTree", nodeCounter);
            LogicalTreeSaverVisitorRPG logicalSaver = new LogicalTreeSaverVisitorRPG(sensorContext, this.fileSystem, settings);

            nodeCounter = new NodeCounterVisitor();
            GraphHelper.processGraph(graph, "__CloneRoot__", "CloneTree", nodeCounter);
            CloneTreeSaverVisitorRPG cloneSaver = new CloneTreeSaverVisitorRPG(sensorContext, this.fileSystem);

            LOG.info("      * Initialization done: " + (System.currentTimeMillis() - startTime) + MS);

            LOG.info("      * Processing LogicalTree...");
            GraphHelper.processGraph(graph, LOGICAL_ROOT, "LogicalTree", logicalVisitor);
            LOG.info("      * Processing LogicalTree done: " + logicalVisitor.getLogicalTime() + MS);
            logicalVisitor = null;

            LOG.info("      * Processing PhysicalTree...");
            GraphHelper.processGraph(graph, "__PhysicalRoot__", "PhysicalTree", physicalVisitor);
            LOG.info("      * Processing PhysicalTree done: " + physicalVisitor.getFileTime() + MS);
            physicalVisitor = null;

            LOG.info("      * Saving LogicalTree...");
            GraphHelper.processGraph(graph, LOGICAL_ROOT, "LogicalTree", logicalSaver);
            logicalSaver.saveLogicalTreeToDatabase(SM_RPG_LOGICAL_LEVEL1, SM_RPG_LOGICAL_LEVEL2, SM_RPG_LOGICAL_LEVEL3);
            LOG.info("      * Saving LogicalTree done: " + logicalSaver.getLogicalTime() + MS);
            logicalSaver = null;

            LOG.info("      * Saving CloneTree...");
            GraphHelper.processGraph(graph, "__CloneRoot__", "CloneTree", cloneSaver);
            cloneSaver.saveCloneTreeToDatabase(SM_RPG_CLONE_TREE);
            LOG.info("      * Saving CloneTree done: " + cloneSaver.getFileTime() + MS);
            cloneSaver = null;

        } catch (VisitorException e) {
            LOG.error("Error during loading data from graph!", e);
        } finally {
            graph = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(SensorDescriptor descriptor) {
        descriptor.onlyOnLanguage(RPG.KEY);
    }

    /**
     * Checks the correctness of sourceMeter's properties.
     *
     * @return True if the properties were set correctly.
     */
    private boolean checkProperties() {
        String pathToCA = this.settings.getString("sm.toolchaindir");
        if (pathToCA == null) {
            LOG.error("RPG SourceMeter path must be set! Check it on the settings page of your SonarQube!");
            return false;
        }

        if (!checkResultsDir()) {
            return false;
        }

        if (!checkProjectName()) {
            return false;
        }

        List<File> sourceDirectories = getSourcesDirectoriesForProject();

        if (sourceDirectories.isEmpty()) {
            LOG.error("No source directories found!");
            return false;
        }

        String baseDir = "";
        try {
            baseDir = sourceDirectories.get(0).getCanonicalPath();
        } catch (IOException e) {
            LOG.warn("Could not get base directory's canonical path. Absolute path is used.");
            baseDir = sourceDirectories.get(0).getAbsolutePath();
        }

        this.commands.add(pathToCA + File.separator
                + RPG.KEY.toUpperCase(Locale.ENGLISH)
                + File.separator + "SourceMeterRPG");
        this.commands.add("-projectBaseDir=" + baseDir);
        this.commands.add("-resultsDir=" + this.resultsDir);
        this.commands.add("-projectName=" + this.projectName);

        String cleanResults = this.settings.getString("sm.cleanresults");
        this.commands.add("-cleanResults=" + cleanResults);

        String spoolPattern = this.settings.getString("sm.rpg.spoolPattern");
        this.commands.add("-spoolFileNamePattern=" + spoolPattern);

        String rpg3Pattern = this.settings.getString("sm.rpg.rpg3Pattern");
        this.commands.add("-rpg3FileNamePattern=" + rpg3Pattern);

        String rpg4Pattern = this.settings.getString("sm.rpg.rpg4Pattern");
        this.commands.add("-rpg4FileNamePattern=" + rpg4Pattern);

        String cloneGenealogy = this.settings.getString("sm.cloneGenealogy");
        String cloneMinLines = this.settings.getString("sm.cloneMinLines");
        this.commands.add("-cloneGenealogy=" + cloneGenealogy);
        this.commands.add("-cloneMinLines=" + cloneMinLines);

        String additionalParameters = this.settings.getString("sm.rpg.toolchainOptions");
        if (additionalParameters != null) {
            this.commands.add(additionalParameters);
        }

        String hardFilter = "";
        String hardFilterFilePath = null;

        try {
            hardFilter = getFilterContent();
            hardFilterFilePath = writeHardFilterToFile(hardFilter);
        } catch (IOException e) {
            LOG.warn(
                    "Cannot create hardFilter file for toolchain! No hardFilter is used during analyzis.",
                    e);
        }

        if (hardFilterFilePath != null) {
            this.commands.add("-externalHardFilter=" + hardFilterFilePath);
        }

        ProfileInitializer profileInitializer = new ProfileInitializer(
                this.settings, getMetricHunterCategories(), this.profile,
                new SourceMeterRPGRuleRepository(new RulesDefinitionXmlLoader()), rules);

        String thresholdPath = this.fileSystem.workDir() + File.separator
                + "SM-Profile.xml";
        try {
            profileInitializer.generatePofileFile(thresholdPath);
            this.commands.add("-profileXML=" + thresholdPath);
        } catch (IOException e) {
            LOG.warn("An error occured while creating SourceMeter profile file. Default profile is used!!", e);
        }

        return true;
    }

    /**
     * Generate MetricHunterCategories, stored in XML file.
     *
     * @return List of MetricHunterCategories
     */
    protected List<MetricHunterCategory> getMetricHunterCategories() {
        List<MetricHunterCategory> categories = new ArrayList<MetricHunterCategory>();

        InputStream xmlFile = null;
        try {
            xmlFile = getClass().getResourceAsStream(THRESHOLD_PROPERTIES_PATH);
            categories.add(new MetricHunterCategory("Program",
                    SourceMeterRPGMetrics.getProgramThresholdMetrics(xmlFile)));

            xmlFile = getClass().getResourceAsStream(THRESHOLD_PROPERTIES_PATH);
            categories.add(new MetricHunterCategory("Procedure",
                    SourceMeterRPGMetrics.getProcedureThresholdMetrics(xmlFile)));

            xmlFile = getClass().getResourceAsStream(THRESHOLD_PROPERTIES_PATH);
            categories.add(new MetricHunterCategory("Subroutine",
                    SourceMeterRPGMetrics.getSubroutineThresholdMetrics(xmlFile)));

            xmlFile = getClass().getResourceAsStream(THRESHOLD_PROPERTIES_PATH);
            categories.add(new MetricHunterCategory("CloneClass",
                    ThresholdPropertiesHelper.getCloneClassThresholdMetrics(xmlFile)));
            xmlFile = getClass().getResourceAsStream(THRESHOLD_PROPERTIES_PATH);
            categories.add(new MetricHunterCategory("CloneInstance",
                    ThresholdPropertiesHelper.getCloneInstanceThresholdMetrics(xmlFile)));
        } finally {
            IOUtils.closeQuietly(xmlFile);
        }
        return categories;
    }

    /**
     * Checks if the "sm.resultsdir" property were set correctly.
     *
     * @return True, if the "sm.resultsdir" property were set correctly, false otherwise.
     */
    private boolean checkResultsDir() {
        this.resultsDir = this.settings.getString("sm.resultsdir");
        if (resultsDir == null) {
            LOG.error("Results directory must be set! Check it on the settings page of your SonarQube!");
            return false;
        }
        return true;
    }

    /**
     * Checks if the "sonar.projectKey" property were set correctly.
     * Sets the project name based on project key.
     *
     * @return True, if the "sonar.projectKey" property were set correctly, false otherwise.
     */
    private boolean checkProjectName() {
        this.projectName = this.settings.getString("sonar.projectKey");
        if (projectName == null) {
            LOG.error("Project key must be set! Key: sonar.projectKey");
            return false;
        }
        this.projectName = StringUtils.replace(this.projectName, ":", "_");
        return true;
    }
}
