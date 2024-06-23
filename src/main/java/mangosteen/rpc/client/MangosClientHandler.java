package mangosteen.rpc.client;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class MangosClientHandler extends SimpleChannelInboundHandler<Object> {

    private Channel channel;

    @Getter
    @EqualsAndHashCode.Include
    private SocketAddress remoteAddress;

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        this.channel = ctx.channel();
        log.info("Channel has registered.");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.remoteAddress = ctx.channel().remoteAddress();
        log.info("Channel is active. remote:{}", remoteAddress);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        // TODO
    }

    public void close(){
        /*
            netty 提供的一种主动关闭连接的机制，
            发送一个空的 BUFFER 然后 ChannelFutureListener
             监听器就会将这个 ChannelFuture 关闭，和释放相关的资源
         */
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }
}
