package com.continuousauth.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * gRPC 双向流服务定义
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.1)",
    comments = "Source: sensor_data.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class SensorDataServiceGrpc {

  private SensorDataServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "com.continuousauth.proto.SensorDataService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.continuousauth.proto.DataPacket,
      com.continuousauth.proto.ServerDirective> getStreamSensorDataMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamSensorData",
      requestType = com.continuousauth.proto.DataPacket.class,
      responseType = com.continuousauth.proto.ServerDirective.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.continuousauth.proto.DataPacket,
      com.continuousauth.proto.ServerDirective> getStreamSensorDataMethod() {
    io.grpc.MethodDescriptor<com.continuousauth.proto.DataPacket, com.continuousauth.proto.ServerDirective> getStreamSensorDataMethod;
    if ((getStreamSensorDataMethod = SensorDataServiceGrpc.getStreamSensorDataMethod) == null) {
      synchronized (SensorDataServiceGrpc.class) {
        if ((getStreamSensorDataMethod = SensorDataServiceGrpc.getStreamSensorDataMethod) == null) {
          SensorDataServiceGrpc.getStreamSensorDataMethod = getStreamSensorDataMethod =
              io.grpc.MethodDescriptor.<com.continuousauth.proto.DataPacket, com.continuousauth.proto.ServerDirective>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamSensorData"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.DataPacket.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.ServerDirective.getDefaultInstance()))
              .build();
        }
      }
    }
    return getStreamSensorDataMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.continuousauth.proto.PolicyRequest,
      com.continuousauth.proto.PolicyUpdate> getGetInitialPolicyMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetInitialPolicy",
      requestType = com.continuousauth.proto.PolicyRequest.class,
      responseType = com.continuousauth.proto.PolicyUpdate.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.continuousauth.proto.PolicyRequest,
      com.continuousauth.proto.PolicyUpdate> getGetInitialPolicyMethod() {
    io.grpc.MethodDescriptor<com.continuousauth.proto.PolicyRequest, com.continuousauth.proto.PolicyUpdate> getGetInitialPolicyMethod;
    if ((getGetInitialPolicyMethod = SensorDataServiceGrpc.getGetInitialPolicyMethod) == null) {
      synchronized (SensorDataServiceGrpc.class) {
        if ((getGetInitialPolicyMethod = SensorDataServiceGrpc.getGetInitialPolicyMethod) == null) {
          SensorDataServiceGrpc.getGetInitialPolicyMethod = getGetInitialPolicyMethod =
              io.grpc.MethodDescriptor.<com.continuousauth.proto.PolicyRequest, com.continuousauth.proto.PolicyUpdate>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetInitialPolicy"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.PolicyRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.PolicyUpdate.getDefaultInstance()))
              .build();
        }
      }
    }
    return getGetInitialPolicyMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.continuousauth.proto.Heartbeat,
      com.continuousauth.proto.HeartbeatAck> getSendHeartbeatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendHeartbeat",
      requestType = com.continuousauth.proto.Heartbeat.class,
      responseType = com.continuousauth.proto.HeartbeatAck.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.continuousauth.proto.Heartbeat,
      com.continuousauth.proto.HeartbeatAck> getSendHeartbeatMethod() {
    io.grpc.MethodDescriptor<com.continuousauth.proto.Heartbeat, com.continuousauth.proto.HeartbeatAck> getSendHeartbeatMethod;
    if ((getSendHeartbeatMethod = SensorDataServiceGrpc.getSendHeartbeatMethod) == null) {
      synchronized (SensorDataServiceGrpc.class) {
        if ((getSendHeartbeatMethod = SensorDataServiceGrpc.getSendHeartbeatMethod) == null) {
          SensorDataServiceGrpc.getSendHeartbeatMethod = getSendHeartbeatMethod =
              io.grpc.MethodDescriptor.<com.continuousauth.proto.Heartbeat, com.continuousauth.proto.HeartbeatAck>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendHeartbeat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.Heartbeat.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.HeartbeatAck.getDefaultInstance()))
              .build();
        }
      }
    }
    return getSendHeartbeatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.continuousauth.proto.MetricsReport,
      com.continuousauth.proto.MetricsResponse> getReportMetricsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ReportMetrics",
      requestType = com.continuousauth.proto.MetricsReport.class,
      responseType = com.continuousauth.proto.MetricsResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.continuousauth.proto.MetricsReport,
      com.continuousauth.proto.MetricsResponse> getReportMetricsMethod() {
    io.grpc.MethodDescriptor<com.continuousauth.proto.MetricsReport, com.continuousauth.proto.MetricsResponse> getReportMetricsMethod;
    if ((getReportMetricsMethod = SensorDataServiceGrpc.getReportMetricsMethod) == null) {
      synchronized (SensorDataServiceGrpc.class) {
        if ((getReportMetricsMethod = SensorDataServiceGrpc.getReportMetricsMethod) == null) {
          SensorDataServiceGrpc.getReportMetricsMethod = getReportMetricsMethod =
              io.grpc.MethodDescriptor.<com.continuousauth.proto.MetricsReport, com.continuousauth.proto.MetricsResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ReportMetrics"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.MetricsReport.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.continuousauth.proto.MetricsResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getReportMetricsMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static SensorDataServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SensorDataServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SensorDataServiceStub>() {
        @java.lang.Override
        public SensorDataServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SensorDataServiceStub(channel, callOptions);
        }
      };
    return SensorDataServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static SensorDataServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SensorDataServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SensorDataServiceBlockingStub>() {
        @java.lang.Override
        public SensorDataServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SensorDataServiceBlockingStub(channel, callOptions);
        }
      };
    return SensorDataServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static SensorDataServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<SensorDataServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<SensorDataServiceFutureStub>() {
        @java.lang.Override
        public SensorDataServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new SensorDataServiceFutureStub(channel, callOptions);
        }
      };
    return SensorDataServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * gRPC 双向流服务定义
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * 双向流RPC：客户端发送DataPacket，服务器返回ServerDirective
     * </pre>
     */
    default io.grpc.stub.StreamObserver<com.continuousauth.proto.DataPacket> streamSensorData(
        io.grpc.stub.StreamObserver<com.continuousauth.proto.ServerDirective> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamSensorDataMethod(), responseObserver);
    }

    /**
     * <pre>
     * 单向RPC：获取初始策略配置
     * </pre>
     */
    default void getInitialPolicy(com.continuousauth.proto.PolicyRequest request,
        io.grpc.stub.StreamObserver<com.continuousauth.proto.PolicyUpdate> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetInitialPolicyMethod(), responseObserver);
    }

    /**
     * <pre>
     * 单向RPC：心跳检测
     * </pre>
     */
    default void sendHeartbeat(com.continuousauth.proto.Heartbeat request,
        io.grpc.stub.StreamObserver<com.continuousauth.proto.HeartbeatAck> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendHeartbeatMethod(), responseObserver);
    }

    /**
     * <pre>
     * 单向RPC：上报聚合指标
     * </pre>
     */
    default void reportMetrics(com.continuousauth.proto.MetricsReport request,
        io.grpc.stub.StreamObserver<com.continuousauth.proto.MetricsResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getReportMetricsMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service SensorDataService.
   * <pre>
   * gRPC 双向流服务定义
   * </pre>
   */
  public static abstract class SensorDataServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return SensorDataServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service SensorDataService.
   * <pre>
   * gRPC 双向流服务定义
   * </pre>
   */
  public static final class SensorDataServiceStub
      extends io.grpc.stub.AbstractAsyncStub<SensorDataServiceStub> {
    private SensorDataServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SensorDataServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SensorDataServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * 双向流RPC：客户端发送DataPacket，服务器返回ServerDirective
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.continuousauth.proto.DataPacket> streamSensorData(
        io.grpc.stub.StreamObserver<com.continuousauth.proto.ServerDirective> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamSensorDataMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * 单向RPC：获取初始策略配置
     * </pre>
     */
    public void getInitialPolicy(com.continuousauth.proto.PolicyRequest request,
        io.grpc.stub.StreamObserver<com.continuousauth.proto.PolicyUpdate> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetInitialPolicyMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 单向RPC：心跳检测
     * </pre>
     */
    public void sendHeartbeat(com.continuousauth.proto.Heartbeat request,
        io.grpc.stub.StreamObserver<com.continuousauth.proto.HeartbeatAck> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendHeartbeatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * 单向RPC：上报聚合指标
     * </pre>
     */
    public void reportMetrics(com.continuousauth.proto.MetricsReport request,
        io.grpc.stub.StreamObserver<com.continuousauth.proto.MetricsResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getReportMetricsMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service SensorDataService.
   * <pre>
   * gRPC 双向流服务定义
   * </pre>
   */
  public static final class SensorDataServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<SensorDataServiceBlockingStub> {
    private SensorDataServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SensorDataServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SensorDataServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * 单向RPC：获取初始策略配置
     * </pre>
     */
    public com.continuousauth.proto.PolicyUpdate getInitialPolicy(com.continuousauth.proto.PolicyRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetInitialPolicyMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 单向RPC：心跳检测
     * </pre>
     */
    public com.continuousauth.proto.HeartbeatAck sendHeartbeat(com.continuousauth.proto.Heartbeat request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendHeartbeatMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * 单向RPC：上报聚合指标
     * </pre>
     */
    public com.continuousauth.proto.MetricsResponse reportMetrics(com.continuousauth.proto.MetricsReport request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getReportMetricsMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service SensorDataService.
   * <pre>
   * gRPC 双向流服务定义
   * </pre>
   */
  public static final class SensorDataServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<SensorDataServiceFutureStub> {
    private SensorDataServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected SensorDataServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new SensorDataServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * 单向RPC：获取初始策略配置
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.continuousauth.proto.PolicyUpdate> getInitialPolicy(
        com.continuousauth.proto.PolicyRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetInitialPolicyMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 单向RPC：心跳检测
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.continuousauth.proto.HeartbeatAck> sendHeartbeat(
        com.continuousauth.proto.Heartbeat request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendHeartbeatMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * 单向RPC：上报聚合指标
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.continuousauth.proto.MetricsResponse> reportMetrics(
        com.continuousauth.proto.MetricsReport request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getReportMetricsMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_INITIAL_POLICY = 0;
  private static final int METHODID_SEND_HEARTBEAT = 1;
  private static final int METHODID_REPORT_METRICS = 2;
  private static final int METHODID_STREAM_SENSOR_DATA = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_INITIAL_POLICY:
          serviceImpl.getInitialPolicy((com.continuousauth.proto.PolicyRequest) request,
              (io.grpc.stub.StreamObserver<com.continuousauth.proto.PolicyUpdate>) responseObserver);
          break;
        case METHODID_SEND_HEARTBEAT:
          serviceImpl.sendHeartbeat((com.continuousauth.proto.Heartbeat) request,
              (io.grpc.stub.StreamObserver<com.continuousauth.proto.HeartbeatAck>) responseObserver);
          break;
        case METHODID_REPORT_METRICS:
          serviceImpl.reportMetrics((com.continuousauth.proto.MetricsReport) request,
              (io.grpc.stub.StreamObserver<com.continuousauth.proto.MetricsResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_STREAM_SENSOR_DATA:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamSensorData(
              (io.grpc.stub.StreamObserver<com.continuousauth.proto.ServerDirective>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getStreamSensorDataMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              com.continuousauth.proto.DataPacket,
              com.continuousauth.proto.ServerDirective>(
                service, METHODID_STREAM_SENSOR_DATA)))
        .addMethod(
          getGetInitialPolicyMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.continuousauth.proto.PolicyRequest,
              com.continuousauth.proto.PolicyUpdate>(
                service, METHODID_GET_INITIAL_POLICY)))
        .addMethod(
          getSendHeartbeatMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.continuousauth.proto.Heartbeat,
              com.continuousauth.proto.HeartbeatAck>(
                service, METHODID_SEND_HEARTBEAT)))
        .addMethod(
          getReportMetricsMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.continuousauth.proto.MetricsReport,
              com.continuousauth.proto.MetricsResponse>(
                service, METHODID_REPORT_METRICS)))
        .build();
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (SensorDataServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getStreamSensorDataMethod())
              .addMethod(getGetInitialPolicyMethod())
              .addMethod(getSendHeartbeatMethod())
              .addMethod(getReportMetricsMethod())
              .build();
        }
      }
    }
    return result;
  }
}
