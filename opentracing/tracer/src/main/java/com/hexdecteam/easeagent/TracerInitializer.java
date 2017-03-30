package com.hexdecteam.easeagent;

import brave.Tracer;
import brave.internal.Platform;
import brave.opentracing.BraveTracer;
import brave.sampler.CountingSampler;
import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Codec;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.reporter.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static zipkin.BinaryAnnotation.create;

@AutoService(Plugin.class)
public class TracerInitializer implements Plugin<TracerInitializer.Configuration> {

    static final Logger LOGGER = LoggerFactory.getLogger(TracerInitializer.class);

    @Override
    public void hook(final Configuration conf, Instrumentation inst, Subscription subs) {
        // TODO Extract GatewaySender to be a sender module.
        final Reporter<Span> reporter = AsyncReporter.builder(new GatewaySender(conf))
                                                     .queuedMaxSpans(conf.reporter_queued_max_spans())
                                                     .messageTimeout(conf.reporter_message_timeout_seconds(), SECONDS)
                                                     .build(encoder(conf));
        final Tracer tracer = Tracer.newBuilder()
                                    .localServiceName(conf.service_name())
                                    .traceId128Bit(conf.trace_id_128b())
                                    .sampler(CountingSampler.create(conf.sample_rate()))
                                    .reporter(reporter)
                                    .build();

        TraceContext.init(BraveTracer.wrap(tracer));
    }

    private Encoder<Span> encoder(final Configuration conf) {
        final Endpoint endpoint = Platform.get().localEndpoint().toBuilder().serviceName(conf.service_name()).build();
        return new Encoder<Span>() {
            @Override
            public Encoding encoding() {
                return Encoding.JSON;
            }

            @Override
            public byte[] encode(Span span) {
                return Codec.JSON.writeSpan(span.toBuilder()
                                                .addBinaryAnnotation(create("system", conf.system(), endpoint))
                                                .addBinaryAnnotation(create("application", conf.application(), endpoint))
                                                .addBinaryAnnotation(create("host_ipv4", conf.host_ipv4(), endpoint))
                                                .addBinaryAnnotation(create("hostname", conf.hostname(), endpoint))
                                                .build());
            }
        };
    }

    @ConfigurationDecorator.Binding("opentracing.tracer")
    static abstract class Configuration {

        abstract String send_endpoint();

        abstract String system();

        abstract String application();

        String service_name() {
            return system() + "-" + application() + "-" + hostname();
        }

        float sample_rate() {
            return 1f;
        }

        boolean send_compression() {
            return false;
        }

        int reporter_queued_max_spans() {
            return 10000;
        }

        long reporter_message_timeout_seconds() {
            return 1;
        }

        boolean trace_id_128b() {
            return false;
        }

        int message_max_bytes() {
            return 5 * 1024 * 10240;
        }

        int connect_timeout() {
            return 10 * 1000;
        }

        int read_timeout() {
            return 60 * 1000;
        }

        String user_agent() {
            return "easeagent/0.1.0";
        }

        String host_ipv4() {
            return LocalhostAddress.getLocalhostAddr().getHostAddress();
        }

        String hostname() {
            return LocalhostAddress.getLocalhostName();
        }

    }

    private static class GatewaySender implements Sender {
        private final Configuration conf;
        private final Encoding encoding;
        /**
         * close is typically called from a different thread
         */
        volatile boolean closeCalled;


        public GatewaySender(Configuration conf) {
            this.conf = conf;
            encoding = Encoding.JSON;
        }

        @Override
        public Encoding encoding() {
            return encoding;
        }

        @Override
        public int messageMaxBytes() {
            return conf.message_max_bytes();
        }

        @Override
        public int messageSizeInBytes(List<byte[]> encodedSpans) {
            return encoding().listSizeInBytes(encodedSpans);
        }

        @Override
        public void sendSpans(List<byte[]> encodedSpans, Callback callback) {
            if (closeCalled) throw new IllegalStateException("close");
            try {
                byte[] message = BytesMessageEncoder.JSON.encode(encodedSpans);
                send(message, "application/json");
                callback.onComplete();
            } catch (Throwable e) {
                callback.onError(e);
                if (e instanceof Error) throw (Error) e;
            }
        }

        @Override
        public CheckResult check() {
            try {
                send(new byte[]{'[', ']'}, "application/json");
                return CheckResult.OK;
            } catch (Exception e) {
                return CheckResult.failed(e);
            }

        }

        @Override
        public void close() throws IOException {
            closeCalled = true;
        }

        void send(byte[] body, String mediaType) throws IOException {
            // intentionally not closing the connection, so as to use keep-alives
            HttpURLConnection connection = (HttpURLConnection) new URL(conf.send_endpoint()).openConnection();
            connection.setConnectTimeout(conf.connect_timeout());
            connection.setReadTimeout(conf.read_timeout());
            connection.setRequestMethod("POST");
            connection.addRequestProperty("Content-Type", mediaType);
            connection.addRequestProperty("User-Agent", conf.user_agent());
            if (conf.send_compression()) {
                connection.addRequestProperty("Content-Encoding", "gzip");
                ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
                GZIPOutputStream compressor = new GZIPOutputStream(gzipped);
                try {
                    compressor.write(body);
                } finally {
                    compressor.close();
                }
                body = gzipped.toByteArray();
            }
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            try {
                final int code = connection.getResponseCode();
                InputStream in = connection.getInputStream();
                try {
                    while (in.read() != -1) ; // skip
                } finally {
                    in.close();
                }
                if (code >= 400) throw new IOException(connection.getResponseMessage());
            } catch (IOException e) {
                InputStream err = connection.getErrorStream();
                try {
                    if (err != null) { // possible, if the connection was dropped
                        while (err.read() != -1) ; // skip
                    }
                } finally {
                    err.close();
                }
                LOGGER.error("Send failed", e);
            }
        }

    }
}
