package com.wechat.jxsc.fulfillment;

import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class LogisticsConfiguration {
    @Bean
    LogisticsProvider logisticsProvider(Environment e, Clock clock) {
        if (!e.acceptsProfiles(Profiles.of("prod"))
                && e.acceptsProfiles(Profiles.of("dev", "test"))
                && "DEV".equals(e.getProperty("jxsc.providers.logistics")))
            return (carrier, tracking) ->
                    new LogisticsProvider.Tracking(
                            "IN_TRANSIT",
                            true,
                            List.of(
                                    new FulfillmentDtos.Event(
                                            clock.instant(), "DEV simulated shipment accepted")));
        return new Kuaidi100Provider(e);
    }
}
