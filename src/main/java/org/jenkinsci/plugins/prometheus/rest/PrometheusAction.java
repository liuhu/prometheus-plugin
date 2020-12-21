package org.jenkinsci.plugins.prometheus.rest;

import com.google.inject.Inject;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import io.prometheus.client.exporter.common.TextFormat;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.prometheus.config.PrometheusConfiguration;
import org.jenkinsci.plugins.prometheus.service.PrometheusMetrics;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Extension
public class PrometheusAction implements UnprotectedRootAction {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusAction.class);

    private PrometheusMetrics prometheusMetrics;
    private final Object summaryMapLock = new Object();
    private final Object gaugeMapLock = new Object();
    private static final Map<String, Summary> summaryMap = new ConcurrentHashMap<>();
    private static final Map<String, Gauge> gaugeMap = new ConcurrentHashMap<>();

    @Inject
    public void setPrometheusMetrics(PrometheusMetrics prometheusMetrics) {
        this.prometheusMetrics = prometheusMetrics;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Prometheus Metrics Exporter";
    }

    @Override
    public String getUrlName() {
        return PrometheusConfiguration.get().getUrlName();
    }

    public HttpResponse doDynamic(StaplerRequest request) {
        logger.debug("Request path: " + request.getRestOfPath());
        if (request.getRestOfPath().equals(PrometheusConfiguration.get().getAdditionalPath())) {
            if (hasAccess()) {
                return prometheusResponse();
            }
            return HttpResponses.forbidden();
        }
        if (request.getRestOfPath().equals(PrometheusConfiguration.get().getAdditionalPath() + "/ci")) {
            if (request.getMethod().equals("POST")) {
                recordMetrics(request);
            }
            return HttpResponses.okJSON();
        }
        return HttpResponses.notFound();
    }

    /**
     * 记录度量信息
     * @param request
     */
    private void recordMetrics(StaplerRequest request) {
        logger.info("Record metrics");
        Map<String, String[]> parameterMap = request.getParameterMap();
        logger.info("post: {}", parameterMap);
        String[] metricName = parameterMap.get("metric_name");
        if (null == metricName || metricName.length != 1) {
            return;
        }
        String[] metricValue = parameterMap.get("metric_value");
        if (null == metricValue || metricValue.length != 1) {
            return;
        }
        double value;
        try {
            value = Double.parseDouble(metricValue[0]);
        } catch (NumberFormatException e) {
            logger.error("Number format error, value = {}", metricValue[0]);
            return;
        }
        Summary summary = getOrRegisterSummary(parameterMap,  metricName[0] + "_summary");
        summary.labels(getLabelValues(parameterMap)).observe(value);

        Gauge gauge = getOrRegisterGauge(parameterMap, metricName[0] + "_max");
        gauge.labels(getLabelValues(parameterMap)).set(value);
    }

    private Summary getOrRegisterSummary(Map<String, String[]> parameterMap, String metricName) {
        Summary summary = summaryMap.get(metricName);
        if (null == summary) {
            synchronized (summaryMapLock) {
                summary = summaryMap.get(metricName);
                if (summary == null) {
                    summary = Summary.build()
                            .help("metric ci")
                            .name(metricName)
                            .labelNames(getLabelNames(parameterMap))
                            .quantile(0.5, 0.05)
                            .quantile(0.9, 0.01)
                            .quantile(0.95, 0.005)
                            .quantile(0.99, 0.001)
                            .create();
                    summaryMap.put(metricName, summary);
                    CollectorRegistry.defaultRegistry.register(summary);
                }
            }
        }
        return summary;
    }

    private Gauge getOrRegisterGauge(Map<String, String[]> parameterMap, String metricName) {
        Gauge gauge = gaugeMap.get(metricName);
        if (null == gauge) {
            synchronized (gaugeMapLock) {
                gauge = gaugeMap.get(metricName);
                if (gauge == null) {
                    gauge = Gauge.build()
                            .help("metric ci")
                            .name(metricName)
                            .labelNames(getLabelNames(parameterMap))
                            .create();
                    gaugeMap.put(metricName, gauge);
                    CollectorRegistry.defaultRegistry.register(gauge);
                }
            }
        }
        return gauge;
    }

    private static String[] getLabelValues(Map<String, String[]> parameterMap) {
        return parameterMap.entrySet().stream()
                .filter(x -> !Arrays.asList("metric_name", "metric_value").contains(x.getKey()))
                .filter(x -> x.getValue().length == 1)
                .map((k) -> k.getValue()[0]).toArray(String[]::new);
    }

    private static String[] getLabelNames(Map<String, String[]> parameterMap) {
        return parameterMap.entrySet().stream()
                .filter(x -> !Arrays.asList("metric_name", "metric_value").contains(x.getKey()))
                .filter(x -> x.getValue().length == 1)
                .map(Map.Entry::getKey).toArray(String[]::new);
    }

    private boolean hasAccess() {
        if (PrometheusConfiguration.get().isUseAuthenticatedEndpoint()) {
            return Jenkins.getInstance().hasPermission(Metrics.VIEW);
        }
        return true;
    }

    private HttpResponse prometheusResponse() {
        return (request, response, node) -> {
            response.setStatus(StaplerResponse.SC_OK);
            response.setContentType(TextFormat.CONTENT_TYPE_004);
            response.addHeader("Cache-Control", "must-revalidate,no-cache,no-store");
            response.getWriter().write(prometheusMetrics.getMetrics());
        };
    }
}
