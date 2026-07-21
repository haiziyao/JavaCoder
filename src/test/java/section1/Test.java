package section1;


import com.hzy.config.AppConfig;
import com.hzy.config.ConfigManager;

public class Test {
    @org.junit.jupiter.api.Test
    void getConfig() {
        AppConfig appConfig = ConfigManager.appConfig;

        System.out.println(appConfig);
    }
}