package io.pivotal;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.MicrometerMetricsCollector;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.datadog.DatadogConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 */
public class RabbitMqDatadog {

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();

        Map<String, String> dataCfg = new HashMap<>();
        dataCfg.put("datadog.apiKey", System.getProperty("datadog.apiKey"));
        dataCfg.put("datadog.hostTag", "host");

        DatadogConfig config = new DatadogConfig() {

            @Override
            public Duration step() {
                return Duration.ofSeconds(5);
            }

            @Override
            public String get(String k) {
                return dataCfg.get(k);
            }
        };

        String hostname = InetAddress.getLocalHost().getHostName();

        for (String dc : new String[] { "us", "europe", "asia" }) {
            CompositeMeterRegistry compositeMeterRegistry = new CompositeMeterRegistry();
            MeterRegistry datadogRegistry = new DatadogMeterRegistry(config, Clock.SYSTEM);
            MeterRegistry jmxRegistry = new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);

            Tags tags = Tags.of("host", hostname, "dc", dc);
            new ClassLoaderMetrics(tags).bindTo(compositeMeterRegistry);
            new JvmMemoryMetrics(tags).bindTo(compositeMeterRegistry);
            new JvmGcMetrics(tags).bindTo(compositeMeterRegistry);
            new ProcessorMetrics(tags).bindTo(compositeMeterRegistry);
            new JvmThreadMetrics(tags).bindTo(compositeMeterRegistry);

            compositeMeterRegistry.add(datadogRegistry);
            compositeMeterRegistry.add(jmxRegistry);

            ConnectionFactory connectionFactory = new ConnectionFactory();
            MicrometerMetricsCollector metricsCollector = new MicrometerMetricsCollector(
                compositeMeterRegistry, "rabbitmq.client", tags
            );
            connectionFactory.setMetricsCollector(metricsCollector);

            Connection connection = connectionFactory.newConnection();
            Channel channel = connection.createChannel();
            String queue = channel.queueDeclare().getQueue();
            channel.basicConsume(queue, true, (ctag, msg) -> {
            }, (ctag) -> {
            });
            executor.submit(() -> {
                Random random = new Random();
                int offset = dc.length() * 10;
                while (true) {
                    Thread.sleep(random.nextInt(100) + offset);
                    channel.basicPublish("", queue, null, "".getBytes());
                }
            });
        }
    }
}
