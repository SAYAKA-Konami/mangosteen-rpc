import lombok.extern.slf4j.Slf4j;

public class MangosteenRPCTest {
    public static void main(String[] args) {
        MangosteenLog.log();
    }
}

@Slf4j
class MangosteenLog {
    static void log(){
        log.info("Hello world mangosteen!");
        log.warn("Warn mangosteen");
        log.error("fuck!");
    }
}
