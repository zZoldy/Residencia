package com.app.residencia;

import com.mysql.cj.jdbc.AbandonedConnectionCleanupThread;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

// A anotação @WebListener faz o Tomcat detectar esta classe automaticamente
@WebListener
public class AppShutdownListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // Opcional: O que fazer quando a Matrix (Tomcat) ligar
        System.out.println("[SYS] Conectando ao mainframe da Residência...");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // O que fazer quando o Tomcat desligar ou reiniciar (Clean & Build)
        System.out.println("[SYS] Encerrando conexões. Iniciando protocolo de limpeza...");

        // 1. Mata a Thread Zumbi do MySQL que estava causando o seu erro
        try {
            AbandonedConnectionCleanupThread.checkedShutdown();
            System.out.println("[SYS] CleanupThread do MySQL finalizada.");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. Desregistra os Drivers do Banco de Dados para liberar a memória
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == cl) {
                try {
                    DriverManager.deregisterDriver(driver);
                    System.out.println("[SYS] Driver JDBC desregistrado: " + driver);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}