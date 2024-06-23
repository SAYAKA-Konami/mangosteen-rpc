package mangosteen.rpc.client.exception;

public class ClientException extends RuntimeException{
    public static final String ADDRESS_ILLEGAL = "Address is illegal";
    public ClientException(String message) {
        super(message);
    }

    public ClientException(Throwable cause) {
        super(cause);
    }
}
