/*
 * ZAP Plugin for SonarQube
 * Copyright (C) 2015-2017 Steve Springett
 * steve.springett@owasp.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.zaproxy;

import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang.StringUtils;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.rule.Rules;
import org.sonar.api.batch.rule.Severity;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.internal.DefaultIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.api.utils.log.Profiler;
import org.sonar.zaproxy.base.ZapMetrics;
import org.sonar.zaproxy.base.ZapUtils;
import org.sonar.zaproxy.parser.ReportParser;
import org.sonar.zaproxy.parser.XmlReportFile;
import org.sonar.zaproxy.parser.element.AlertItem;
import org.sonar.zaproxy.parser.element.ZapReport;
import org.xml.sax.SAXException;

public class ZapSensor implements Sensor {

	private static final String SENSOR_NAME = "OWASP Zap-Check";
	private static final Logger LOGGER = Loggers.get(ZapSensor.class);

	private final Rules rules;
	private final XmlReportFile report;

	private int totalAlerts;
	private int criticalIssuesCount;
	private int majorIssuesCount;
	private int minorIssuesCount;
	private int infoIssuesCount;

	public ZapSensor(
			ZapSensorConfiguration configuration,
			FileSystem fileSystem,
			PathResolver pathResolver,
			Rules rules) {
		this.rules = rules;
		this.report = new XmlReportFile(configuration, fileSystem, pathResolver);
	}

	private void addIssue(org.sonar.api.batch.sensor.SensorContext context, AlertItem alert) {
		Severity severity = ZapUtils.riskCodeToSonarQubeSeverity(alert.getRiskcode());
		context.newIssue()
				.forRule(RuleKey.of(ZapPlugin.REPOSITORY_KEY, String.valueOf(alert.getPluginid())))
				.at(new DefaultIssueLocation().on(context.module()).message(formatDescription(alert)))
				.overrideSeverity(severity)
				.save();

		incrementCount(severity);
	}

	/**
	 * todo: Add Markdown formatting if and when Sonar supports it https://jira.codehaus.org/browse/SONAR-4161
	 */
	private String formatDescription(AlertItem alert) {
		StringBuilder sb = new StringBuilder();

		sb.append(addValueToDescription("URI", alert.getUri(), false));
		sb.append(addValueToDescription("Confidence", String.valueOf(alert.getConfidence()), false));
		sb.append(addValueToDescription("Description", alert.getDesc(), false));
		sb.append(addValueToDescription("Param", alert.getParam(), false));
		sb.append(addValueToDescription("Attack", alert.getAttack(), false));
		sb.append(addValueToDescription("Evidence", alert.getEvidence(), true));

		return sb.toString();
	}

	private String addValueToDescription(String name, String value, boolean isEnd) {
		StringBuilder sb = new StringBuilder();
		if (!StringUtils.isBlank(value)) {
			sb.append(name).append(": ").append(value);
			if (!isEnd) {
				sb.append(" | ");
			}
		}
		return sb.toString();
	}

	private void incrementCount(Severity severity) {
		switch (severity) {
			case CRITICAL:
				this.criticalIssuesCount++;
				break;
			case MAJOR:
				this.majorIssuesCount++;
				break;
			case MINOR:
				this.minorIssuesCount++;
				break;
			case INFO:
				this.infoIssuesCount++;
				break;
		}
	}

	private void addIssues(org.sonar.api.batch.sensor.SensorContext context, ZapReport zapReport) {
		if (zapReport.getSite().getAlerts() == null) {
			return;
		}
		for (AlertItem alert : zapReport.getSite().getAlerts()) {
			addIssue(context, alert);
		}
	}

	private ZapReport parseZapReport() throws IOException, ParserConfigurationException, SAXException {
		InputStream stream = this.report.getInputStream();
		if (stream == null) {
			return null;
		}
		try {
			return new ReportParser().parse(stream);
		} finally {
			stream.close();
		}

	}

	@Override
	public void execute(org.sonar.api.batch.sensor.SensorContext context) {
		Profiler profiler = Profiler.create(LOGGER);
		profiler.startInfo("Process ZAP report");
		try {
			ZapReport zapReport = parseZapReport();
			if (zapReport != null) {
				totalAlerts = zapReport.getSite().getAlerts().size();
				addIssues(context, zapReport);
			}
		} catch (Exception e) {
			throw new RuntimeException(
					"Can not process ZAP report. Ensure the report are located within the project workspace and that sonar.sources is set to reflect these paths (or set sonar.sources=.)",
					e);
		} finally {
			profiler.stopInfo();
		}
		saveMeasures(context);
	}

	private void saveMeasures(SensorContext context) {
		context.newMeasure().forMetric(ZapMetrics.HIGH_RISK_ALERTS).withValue((double) criticalIssuesCount);
		context.newMeasure().forMetric(ZapMetrics.MEDIUM_RISK_ALERTS).withValue((double) majorIssuesCount);
		context.newMeasure().forMetric(ZapMetrics.LOW_RISK_ALERTS).withValue((double) minorIssuesCount);
		context.newMeasure().forMetric(ZapMetrics.INFO_RISK_ALERTS).withValue((double) infoIssuesCount);
		context.newMeasure().forMetric(ZapMetrics.TOTAL_ALERTS).withValue((double) totalAlerts);

		context.newMeasure().forMetric(ZapMetrics.IDENTIFIED_RISK_SCORE).withValue(
				ZapMetrics.inheritedRiskScore(criticalIssuesCount, majorIssuesCount, minorIssuesCount));
	}

	@Override
	public String toString() {
		return "OWASP Zed Attack Proxy";
	}

	@Override
	public void describe(SensorDescriptor sensorDescriptor) {
		sensorDescriptor.name(SENSOR_NAME);
	}
}
