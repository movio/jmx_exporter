package io.prometheus.jmx;

import java.io.File;
import java.io.FilenameFilter;
import java.io.FileReader;
import java.util.UUID;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Enumeration;

import io.prometheus.client.Collector;
import io.prometheus.client.exporter.common.TextFormat;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.MetricsServlet;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class WebServer {

   private static final CollectorRegistry registry = CollectorRegistry.defaultRegistry;

   public static final FilenameFilter FILENAME_FILTER = new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
         return name.endsWith(".yml") || name.endsWith(".yaml");
      }
   };

   public static void main(String[] args) throws Exception {
     if (args.length < 2) {
       System.err.println("Usage: WebServer <port> <yaml configuration files directory>");
       System.exit(1);
     }

     File configDirectory = new File(args[1]);

     if (!configDirectory.exists()) {
        System.err.println("Configuration directory does not exist: [" + configDirectory.getPath() + "]");
        System.exit(1);
     }

     File[] files = configDirectory.listFiles(FILENAME_FILTER);

     if (files.length == 0) {
        System.err.println("No configuration files found in [" + configDirectory.getPath() + "]");
        System.exit(1);
     }

     for (File file : files) {
        new JmxCollector(new FileReader(file)).register();
     }

     int port = Integer.parseInt(args[0]);
     Server server = new Server(port);
     ServletContextHandler context = new ServletContextHandler();
     context.setContextPath("/");
     server.setHandler(context);
     context.addServlet(new ServletHolder(new MetricsServlet(registry) {

        @Override
        protected void doGet(final HttpServletRequest req, final HttpServletResponse resp)
            throws ServletException, IOException {
          resp.setStatus(HttpServletResponse.SC_OK);
          resp.setContentType(TextFormat.CONTENT_TYPE_004);

          Writer writer = resp.getWriter();
          write005(writer, registry.metricFamilySamples());
          writer.flush();
          writer.close();
        }

         String escapeHelp(String s) {
          return s.replace("\\", "\\\\").replace("\n", "\\n");
        }

        String escapeLabelValue(String s) {
          return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }

         String typeString(Collector.Type t) {
          switch (t) {
            case GAUGE:
              return "gauge";
            case COUNTER:
              return "counter";
            case SUMMARY:
              return "summary";
            case HISTOGRAM:
              return "histogram";
            default:
              return "untyped";
          }
        }

        public void write005(Writer writer, Enumeration<Collector.MetricFamilySamples> mfs) throws IOException {
            /* See https://docs.google.com/a/boxever.com/document/d/1ZjyKiKxZV83VI9ZKAXRGKaUKK2BIWCT7oiGBKDBpjEY/edit#
             * for the output format specification. */
            for (Collector.MetricFamilySamples metricFamilySamples: Collections.list(mfs)) {
              writer.write("# HELP " + UUID.randomUUID().toString().replaceAll("-", "_") + "_" + metricFamilySamples.name + " " + escapeHelp(metricFamilySamples.help) + "\n");
              writer.write("# TYPE " + metricFamilySamples.name + " " + typeString(metricFamilySamples.type) + "\n");
              for (Collector.MetricFamilySamples.Sample sample: metricFamilySamples.samples) {
                writer.write(sample.name);
                if (sample.labelNames.size() > 0) {
                  writer.write("{");
                  for (int i = 0; i < sample.labelNames.size(); ++i) {
                    writer.write(String.format("%s=\"%s\",",
                        sample.labelNames.get(i),  escapeLabelValue(sample.labelValues.get(i))));
                  }
                  writer.write("}");
                }
                writer.write(" " + Collector.doubleToGoString(sample.value) + "\n");
              }
            }
          }

     }), "/metrics");
     server.start();
     server.join();
   }
}
