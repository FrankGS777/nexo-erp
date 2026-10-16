package dev.nexo.erp.web;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class EnvironmentAdvice {
    private final Environment environment;
    public EnvironmentAdvice(Environment environment) {this.environment=environment;}
    @ModelAttribute("localEnvironment")
    public boolean localEnvironment() {
        return java.util.Arrays.asList(environment.getActiveProfiles()).contains("local");
    }
}
