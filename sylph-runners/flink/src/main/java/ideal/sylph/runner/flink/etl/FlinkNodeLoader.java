/*
 * Copyright (C) 2018 The Sylph Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ideal.sylph.runner.flink.etl;

import ideal.sylph.etl.api.RealTimeSink;
import ideal.sylph.etl.api.RealTimeTransForm;
import ideal.sylph.etl.api.Sink;
import ideal.sylph.etl.api.Source;
import ideal.sylph.etl.api.TransForm;
import ideal.sylph.spi.Binds;
import ideal.sylph.spi.NodeLoader;
import ideal.sylph.spi.exception.SylphException;
import ideal.sylph.spi.model.PipelinePluginManager;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.UnaryOperator;

import static ideal.sylph.spi.exception.StandardErrorCode.JOB_BUILD_ERROR;
import static java.util.Objects.requireNonNull;

public final class FlinkNodeLoader
        implements NodeLoader<DataStream<Row>>
{
    private static final Logger logger = LoggerFactory.getLogger(FlinkNodeLoader.class);
    private final PipelinePluginManager pluginManager;
    private final Binds binds;

    public FlinkNodeLoader(PipelinePluginManager pluginManager, Binds binds)
    {
        this.pluginManager = requireNonNull(pluginManager, "binds is null");
        this.binds = requireNonNull(binds, "binds is null");
    }

    @Override
    public UnaryOperator<DataStream<Row>> loadSource(String driverStr, final Map<String, Object> config)
    {
        try {
            final Class<? extends Source<DataStream<Row>>> driverClass = (Class<? extends Source<DataStream<Row>>>) pluginManager.loadPluginDriver(driverStr);
            final Source<DataStream<Row>> source = getInstance(driverClass, config);

            return (stream) -> {
                logger.info("source {} schema:{}", driverClass, source.getSource().getType());
                return source.getSource();
            };
        }
        catch (Exception e) {
            throw new SylphException(JOB_BUILD_ERROR, e);
        }
    }

    @Override
    public UnaryOperator<DataStream<Row>> loadSink(String driverStr, final Map<String, Object> config)
    {
        final Object driver;
        try {
            Class<?> driverClass = pluginManager.loadPluginDriver(driverStr);
            driver = getInstance(driverClass, config);
        }
        catch (Exception e) {
            throw new SylphException(JOB_BUILD_ERROR, e);
        }

        final Sink<DataStream<Row>> sink;
        if (driver instanceof RealTimeSink) {
            sink = loadRealTimeSink((RealTimeSink) driver);
        }
        else if (driver instanceof Sink) {
            sink = (Sink<DataStream<Row>>) driver;
        }
        else {
            throw new SylphException(JOB_BUILD_ERROR, "NOT SUPPORTED Sink:" + driver);
        }

        return (stream) -> {
            requireNonNull(stream, "Sink find input stream is null");
            sink.run(stream);
            return null;
        };
    }

    @Override
    public Binds getBinds()
    {
        return binds;
    }

    /**
     * transform api
     **/
    @Override
    public final UnaryOperator<DataStream<Row>> loadTransform(String driverStr, final Map<String, Object> config)
    {
        final Object driver;
        try {
            Class<?> driverClass = pluginManager.loadPluginDriver(driverStr);
            driver = getInstance(driverClass, config);
        }
        catch (Exception e) {
            throw new SylphException(JOB_BUILD_ERROR, e);
        }

        final TransForm<DataStream<Row>> transform;
        if (driver instanceof RealTimeTransForm) {
            transform = loadRealTimeTransForm((RealTimeTransForm) driver);
        }
        else if (driver instanceof TransForm) {
            transform = (TransForm<DataStream<Row>>) driver;
        }
        else {
            throw new SylphException(JOB_BUILD_ERROR, "NOT SUPPORTED TransForm:" + driver);
        }

        return (stream) -> {
            requireNonNull(stream, "Transform find input stream is null");
            DataStream<Row> dataStream = transform.transform(stream);
            logger.info("transfrom {} schema to:", driver, dataStream.getType());
            return dataStream;
        };
    }

    private static Sink<DataStream<Row>> loadRealTimeSink(RealTimeSink realTimeSink)
    {
        // or user stream.addSink(new FlinkSink(realTimeSink, stream.getType()));
        return (Sink<DataStream<Row>>) stream -> stream.addSink(new FlinkSink(realTimeSink, stream.getType()));
    }

    private static TransForm<DataStream<Row>> loadRealTimeTransForm(RealTimeTransForm realTimeTransForm)
    {
        return (TransForm<DataStream<Row>>) stream -> {
            final SingleOutputStreamOperator<Row> tmp = stream
                    .flatMap(new FlinkTransFrom(realTimeTransForm, stream.getType()));
            // schema必须要在driver上面指定
            ideal.sylph.etl.Row.Schema schema = realTimeTransForm.getRowSchema();
            if (schema != null) {
                RowTypeInfo outPutStreamType = FlinkRow.parserRowType(schema);
                return tmp.returns(outPutStreamType);
            }
            return tmp;
        };
    }
}
