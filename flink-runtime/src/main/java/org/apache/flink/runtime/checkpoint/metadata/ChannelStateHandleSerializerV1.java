/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.checkpoint.metadata;

import org.apache.flink.runtime.state.AbstractChannelStateHandle;
import org.apache.flink.runtime.state.AbstractChannelStateHandle.StateContentMetaInfo;
import org.apache.flink.runtime.state.InputChannelStateHandle;
import org.apache.flink.runtime.state.InputStateHandle;
import org.apache.flink.runtime.state.OutputStateHandle;
import org.apache.flink.runtime.state.ResultSubpartitionStateHandle;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.util.function.BiConsumerWithException;
import org.apache.flink.util.function.FunctionWithException;
import org.apache.flink.util.function.QuadFunction;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.runtime.checkpoint.metadata.MetadataV2V3SerializerBase.deserializeStreamStateHandle;
import static org.apache.flink.runtime.checkpoint.metadata.MetadataV2V3SerializerBase.serializeStreamStateHandle;
import static org.apache.flink.runtime.state.ChannelStateHelper.INPUT_CHANNEL_INFO_READER;
import static org.apache.flink.runtime.state.ChannelStateHelper.INPUT_CHANNEL_INFO_WRITER;
import static org.apache.flink.runtime.state.ChannelStateHelper.RESULT_SUBPARTITION_INFO_READER;
import static org.apache.flink.runtime.state.ChannelStateHelper.RESULT_SUBPARTITION_INFO_WRITER;

/**
 * ChannelStateHandlerSerializer only support {@link InputChannelStateHandle} and {@link
 * ResultSubpartitionStateHandle}, used in {@link MetadataV3Serializer} and {@link
 * MetadataV4Serializer}.
 */
class ChannelStateHandleSerializerV1 implements ChannelStateHandleSerializer {

    @Override
    public void serialize(OutputStateHandle handle, DataOutputStream dos) throws IOException {
        if (!(handle instanceof ResultSubpartitionStateHandle)) {
            throw new IllegalStateException(
                    "OutputStateHandle must be ResultSubpartitionStateHandle.");
        }

        serializeChannelStateHandle(
                (ResultSubpartitionStateHandle) handle, dos, RESULT_SUBPARTITION_INFO_WRITER);
    }

    @Override
    public OutputStateHandle deserializeOutputStateHandle(
            DataInputStream dis, MetadataV2V3SerializerBase.DeserializationContext context)
            throws IOException {

        return deserializeChannelStateHandle(
                RESULT_SUBPARTITION_INFO_READER, ResultSubpartitionStateHandle::new, dis, context);
    }

    @Override
    public void serialize(InputStateHandle handle, DataOutputStream dos) throws IOException {
        if (!(handle instanceof InputChannelStateHandle)) {
            throw new IllegalStateException("InputStateHandle must be InputChannelStateHandle.");
        }

        serializeChannelStateHandle(
                (InputChannelStateHandle) handle, dos, INPUT_CHANNEL_INFO_WRITER);
    }

    @Override
    public InputStateHandle deserializeInputStateHandle(
            DataInputStream dis, MetadataV2V3SerializerBase.DeserializationContext context)
            throws IOException {

        return deserializeChannelStateHandle(
                INPUT_CHANNEL_INFO_READER, InputChannelStateHandle::new, dis, context);
    }

    static <Info> void serializeChannelStateHandle(
            AbstractChannelStateHandle<Info> handle,
            DataOutputStream dos,
            BiConsumerWithException<Info, DataOutputStream, IOException> infoWriter)
            throws IOException {
        dos.writeInt(handle.getSubtaskIndex());
        infoWriter.accept(handle.getInfo(), dos);
        dos.writeInt(handle.getOffsets().size());
        for (long offset : handle.getOffsets()) {
            dos.writeLong(offset);
        }
        dos.writeLong(handle.getStateSize());
        serializeStreamStateHandle(handle.getDelegate(), dos);
    }

    static <Info, Handle extends AbstractChannelStateHandle<Info>>
            Handle deserializeChannelStateHandle(
                    FunctionWithException<DataInputStream, Info, IOException> infoReader,
                    QuadFunction<Integer, Info, StreamStateHandle, StateContentMetaInfo, Handle>
                            handleBuilder,
                    DataInputStream dis,
                    MetadataV2V3SerializerBase.DeserializationContext context)
                    throws IOException {

        int subtaskIndex = dis.readInt();
        final Info info = infoReader.apply(dis);
        int offsetsSize = dis.readInt();
        final List<Long> offsets = new ArrayList<>(offsetsSize);
        for (int i = 0; i < offsetsSize; i++) {
            offsets.add(dis.readLong());
        }
        final long size = dis.readLong();
        return handleBuilder.apply(
                subtaskIndex,
                info,
                deserializeStreamStateHandle(dis, context),
                new StateContentMetaInfo(offsets, size));
    }
}
