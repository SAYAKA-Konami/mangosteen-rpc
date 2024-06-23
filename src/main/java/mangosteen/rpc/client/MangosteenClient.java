package mangosteen.rpc.client;

public class MangosteenClient {
    private String serverAddress;
    private long timeout;

    public MangosteenClient(String serverAddress, long timeout) {
        this.serverAddress = serverAddress;
        this.timeout = timeout;
        connect();
    }

    private void connect() {
        MangosteenConnectManager.getInstance().connect(this.serverAddress);
    }

    public void stop(){
        MangosteenConnectManager.getInstance().stop();
    }
}
