package mangosteen.rpc.client;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public class RpcClientInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline()
                .addLast(new MangosClientHandler());
    }
}
