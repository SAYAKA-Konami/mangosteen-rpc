package mangosteen.rpc.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import mangosteen.rpc.client.exception.ClientException;
import org.apache.commons.collections.CollectionUtils;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @apiNote RPC客户端。
 * @author wu nan
 * @since  2024/6/22
 **/
@Slf4j
public class MangosteenConnectManager {

    /*
       volatile 在多线程使用时，对象里面的内容可能会发生改变，使用线程可见性 volatile 来修饰
     */
    private static volatile MangosteenConnectManager RPC_CONNECT_MANAGER = new MangosteenConnectManager();

    // 做饥饿单例模式
    private MangosteenConnectManager() {
    }

    public static MangosteenConnectManager getInstance() {
        return RPC_CONNECT_MANAGER;
    }

    /**
     * 一个地址对应一个 client 处理器；存储所有已经连接上的信息
     */
    private final Map<InetSocketAddress, MangosClientHandler> connectedHandlerMap = new ConcurrentHashMap<>();
    private CopyOnWriteArrayList<MangosClientHandler>  connectedHandlerList = new CopyOnWriteArrayList();

    /**
     * 用于异步提交连接的线程池
     */
    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
            5,  // 核心线程
            5, // 最大线程
            60L, // 线程释放空闲时间，这里是 60 + 后面的单位
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), // 允许排队的数量
            new ThreadPoolExecutor.DiscardPolicy() // 拒绝策略：DiscardPolicy 是直接丢弃掉被拒绝的任务
    );

    public static void logTest() {
        log.info("日志测试");
    }


    /**
     * 1. 异步连接：使用线程池真正发起连接，连接失败监听、连接成功监听
     * 2. 对于连接成功后，做一个缓存（管理）
     *
     * @param serverAddress 192.168.1.1:8888,192.168.1.2:8888
     *                      当次需要建立的新的连接，注意：如果第一次给定了 2 个连接，第二次调用只给定了 1 个连接，那么会以第二次会准，
     *                      第一次给定的连接如果不在第 2 次给定的连接里面的，则会被断开连接，并释放 netty 相关的资源
     */
    public void connect(final String serverAddress) {
        List<String> address = Arrays.asList(serverAddress.split(","));
        updateConnectedServer(address);
    }

    /**
     * 更新缓存信息，并异步发起连接
     */
    public void updateConnectedServer(List<String> address) {
        if (CollectionUtils.isEmpty(address)) {
            log.info("没有可用的服务地址");
            // 需要删除掉已有的所有链接资源
            clearConnected(null);
            return;
        }

        // 解析为 InetSocketAddress 集
        HashSet<InetSocketAddress> socketAddresses = new HashSet<>();
        for (String adders : address) {
            String[] items = adders.split(":");
            if (items.length == 2) {
                String host = items[0];
                int port = Integer.parseInt(items[1]);
                InetSocketAddress remoteIsa = new InetSocketAddress(host, port);
                socketAddresses.add(remoteIsa);
            }
        }

        // 发起连接
        for (InetSocketAddress socketAddress : socketAddresses) {
            // 如果已经存在，则不再发起连接
            if (connectedHandlerMap.containsKey(socketAddress)) {
                continue;
            }

            connectAsync(socketAddress);
        }

        // 如果缓存中 connectedHandlerMap 存在 socketAddresses 没有的地址，需要清除该地址的相关资源
        // 这里需要注意下：由于上面是异步连接，所以这里清除的时候，可能新的连接并没有被添加进来
        // 但是对于这里的业务目的，不影响
        Iterator<InetSocketAddress> iterator = connectedHandlerMap.keySet().iterator();
        while (iterator.hasNext()) {
            InetSocketAddress remoteAddress = iterator.next();
            // 如果最新的链接里面不包含缓存中的地址，就要删掉
            if (socketAddresses.contains(remoteAddress)) {
                continue;
            }
            log.info("删除不可用的链接（当次链接列表中不包含旧的链接）：{}", remoteAddress);
            MangosClientHandler rpcClientHandler = connectedHandlerMap.get(remoteAddress);
            rpcClientHandler.close();
            iterator.remove();
        }
    }


    /**
     * 默认是 CPU x 2
     */
    private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup(4);

    /**
     * 异步发起连接
     */
    private void connectAsync(InetSocketAddress socketAddress) {
        threadPoolExecutor.submit(() -> {
            Bootstrap b = new Bootstrap();
            b
                    .group(eventLoopGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    // 这里使用一个自定义的 ChannelInitializer 子类来实现相关 handler 的逻辑封装
                    .handler(new RpcClientInitializer());
            connect(b, socketAddress);
        });
    }

    private void connect(final Bootstrap b, InetSocketAddress socketAddress) {
        // 1. 发起连接
        ChannelFuture channelFuture = b.connect(socketAddress);

        // 2. 连接成功时：添加监听，把连接放入缓存中
        channelFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("成功连接到服务端 {}", socketAddress);
                // 从该通道上注册的 pipeline 中拿到 RpcClientHandler
                MangosClientHandler rpcClientHandler = future.channel().pipeline().get(MangosClientHandler.class);
                // 这里需要注意的是，该回调时机不一定会在 RpcClientHandler.channelActive 方法之后
                // 所以不一定能从 RpcClientHandler 中获取到 socketAddress
                addHandler(socketAddress, rpcClientHandler);
            }
        });

        // 3. 连接失败时：添加监听，清除资源后进行发起重新连接操作

        // 当通过关闭时，添加一个连接
        channelFuture.channel().closeFuture().addListener((ChannelFutureListener) future -> {
            log.info("channel close operationComplete: remote={}", socketAddress);
            // 使用 eventLoop 线程池执行一个延迟任务
            // 这里设置在 3 秒后执行一个任务
            future.channel().eventLoop().schedule(() -> {
                log.warn("连接失败，进行重新连接");
                // 清除连接
                clearConnected(socketAddress);
                // 发起重连
                this.connect(b, socketAddress);
            }, 3, TimeUnit.SECONDS);
        });
    }

    private void addHandler(InetSocketAddress socketAddress, MangosClientHandler handler) {
        connectedHandlerMap.put(socketAddress, handler);
        connectedHandlerList.add(handler);
        signalAvailableHandler();
    }

    private final ReentrantLock connectedLock = new ReentrantLock();
    private final Condition connectedCondition = connectedLock.newCondition();
    private volatile boolean isRunning = true;
    private AtomicInteger handlerIndex = new AtomicInteger(0);

    /**
     * 唤醒可用的业务执行器
     */
    private void signalAvailableHandler() {
        connectedLock.lock();
        try {
            connectedCondition.signalAll();
        } finally {
            connectedLock.unlock();
        }
    }
    /**
     * 等待新链接接入的等待方法
     */
    private boolean waitingForAvailableHandler() throws InterruptedException {
        connectedLock.lock();
        try {
            // 如果在等待时间内被唤醒，则返回true。
            return connectedCondition.await(6000, TimeUnit.MILLISECONDS);
        } finally {
            connectedLock.unlock();
        }
    }

    public MangosClientHandler chooseHandler(){
        CopyOnWriteArrayList<MangosClientHandler> handlers = (CopyOnWriteArrayList) connectedHandlerList.clone();
        int size = handlers.size();
        // Loop if there is no active connect.
        while (isRunning && size == 0){
            try {
                boolean isAvailable = waitingForAvailableHandler();
                if (isAvailable) {
                    // If there comes new connection.
                    handlers = (CopyOnWriteArrayList) connectedHandlerList.clone();
                    size = handlers.size();
                }
            } catch (InterruptedException e) {
                throw new ClientException(e);
            }
        }
        return handlers.get(handlerIndex.addAndGet(1) % size);
    }

    /**
     * 清除连接资源
     * <pre>
     *     1. 断开与服务器的连接
     *     2. 从缓存中 connectedHandlerMap 移除该地址的信息
     * </pre>
     *
     */
    private void clearConnected(InetSocketAddress socketAddress) {
        // 清理已缓存的所有连接资源
        if (socketAddress == null) {
            connectedHandlerMap.forEach((inetSocketAddress, handler) -> {
                handler.close();
            });
            connectedHandlerMap.clear();
            return;
        }

        MangosClientHandler cacheClientHandler = connectedHandlerMap.get(socketAddress);
        if (cacheClientHandler != null) {
            // 删除缓存条目之前，需要先释放资源（断开连接）
            cacheClientHandler.close();
            // 这里解密下，为什么 给 map 一个不同的对象，但是能获取到相同的值?
            // 秘密在于：java.net.InetSocketAddress.InetSocketAddressHolder.hashCode
            // map 是通过 key 的 hashCode 查找内容的，而 InetSocketAddressHolder.hashCode 里面使用的是字符串的 host 的 hashcode + port
            // 这里从 channel 取出来的 SocketAddress 就是 InetSocketAddress，虽然不是和 map 里面的 key 是同一个实例
            connectedHandlerMap.remove(cacheClientHandler);
        }
    }

    public void stop(){
        isRunning = false;
        for (MangosClientHandler mangosClientHandler : connectedHandlerList) {
            mangosClientHandler.close();
        }
        // 唤醒一下，目的是让 chooseHandler() 方法退出来（有可能正在等待）
        signalAvailableHandler();
        // 线程资源优雅的停掉
        threadPoolExecutor.shutdown();
        eventLoopGroup.shutdownGracefully();
    }

    /**
     * 重新建立连接
     * @param handler
     * @param socketAddress
     */
    public void reconnect(final MangosClientHandler handler, InetSocketAddress socketAddress) {
        if (handler != null) {
            handler.close();
            connectedHandlerList.remove(handler);
            connectedHandlerMap.remove(socketAddress);
        }
        connectAsync(socketAddress);
    }

}
