package org.tron.core.net.node;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.io.File;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.overlay.client.PeerClient;
import org.tron.common.overlay.server.ChannelManager;
import org.tron.common.overlay.server.SyncPool;
import org.tron.common.utils.FileUtil;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.WitnessService;

@Slf4j
public abstract class BaseNetTest {

  protected static AnnotationConfigApplicationContext context;
  protected NodeImpl node;
  protected RpcApiService rpcApiService;
  protected PeerClient peerClient;
  protected ChannelManager channelManager;
  protected SyncPool pool;
  protected Manager manager;

  private String dbPath;
  private String dbDirectory;
  private String indexDirectory;

  private static final int port = 17889;

  public BaseNetTest(String dbPath, String dbDirectory, String indexDirectory) {
    this.dbPath = dbPath;
    this.dbDirectory = dbDirectory;
    this.indexDirectory = indexDirectory;
  }

  @Before
  public void init() {
    new Thread(new Runnable() {
      @Override
      public void run() {
        logger.info("Full node running.");
        Args.setParam(
            new String[]{
                "--output-directory", dbPath,
                "--storage-db-directory", dbDirectory,
                "--storage-index-directory", indexDirectory
            },
            "config.conf"
        );
        Args cfgArgs = Args.getInstance();
        cfgArgs.setNodeListenPort(port);
        cfgArgs.setNodeDiscoveryEnable(false);
        cfgArgs.getSeedNode().getIpList().clear();
        cfgArgs.setNeedSyncCheck(false);
        cfgArgs.setNodeExternalIp("127.0.0.1");

        context = new AnnotationConfigApplicationContext(DefaultConfig.class);

        if (cfgArgs.isHelp()) {
          logger.info("Here is the help message.");
          return;
        }
        Application appT = ApplicationFactory.create(context);
        rpcApiService = context.getBean(RpcApiService.class);
        appT.addService(rpcApiService);
        if (cfgArgs.isWitness()) {
          appT.addService(new WitnessService(appT));
        }
        appT.initServices(cfgArgs);
        appT.startServices();

        node = context.getBean(NodeImpl.class);
        peerClient = context.getBean(PeerClient.class);
        channelManager = context.getBean(ChannelManager.class);
        pool = context.getBean(SyncPool.class);
        manager = context.getBean(Manager.class);
        NodeDelegate nodeDelegate = new NodeDelegateImpl(manager);
        node.setNodeDelegate(nodeDelegate);
        pool.init(node);
        
        appT.startup();
        rpcApiService.blockUntilShutdown();
      }
    }).start();
    int tryTimes = 1;
    while (tryTimes <= 30 && (node == null || peerClient == null
        || channelManager == null || pool == null)) {
      try {
        logger.info("node:{},peerClient:{},channelManager:{},pool:{}", node, peerClient,
            channelManager, pool);
        Thread.sleep(1000 * tryTimes);
      } catch (InterruptedException e) {
        e.printStackTrace();
      } finally {
        ++tryTimes;
      }
    }
  }

  protected Channel createClient() throws InterruptedException {
    NioEventLoopGroup group = new NioEventLoopGroup(1);
    Bootstrap b = new Bootstrap();
    b.group(group).channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel ch) throws Exception {
            // limit the size of receiving buffer to 1024
            ch.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(256 * 1024));
            ch.config().setOption(ChannelOption.SO_RCVBUF, 256 * 1024);
            ch.config().setOption(ChannelOption.SO_BACKLOG, 1024);
            ch.pipeline()
                .addLast("readTimeoutHandler", new ReadTimeoutHandler(600, TimeUnit.SECONDS))
                .addLast("writeTimeoutHandler", new WriteTimeoutHandler(600, TimeUnit.SECONDS));
            ch.pipeline().addLast("protoPender", new ProtobufVarint32LengthFieldPrepender());
            ch.pipeline().addLast("lengthDecode", new ProtobufVarint32FrameDecoder());

            // be aware of channel closing
            ch.closeFuture();
          }
        }).option(ChannelOption.SO_KEEPALIVE, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000)
        .option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
    return b.connect("127.0.0.1", port).sync().channel();
  }

  @After
  public void destroy() {
    Args.clearParam();
    FileUtil.deleteDir(new File("output-nodeImplTest"));
  }
}
