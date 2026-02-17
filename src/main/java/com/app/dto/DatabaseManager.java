/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.app.dto;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;

public class DatabaseManager {

    // === 1. CONFIGURAÇÕES CENTRALIZADAS (O Segredo fica aqui) ===
    private static final String URL = "jdbc:mysql://localhost:3306/residencia?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8";
    private static final String USER = "Freecs";
    private static final String PASS = "#SQLUser02"; // <--- SUA SENHA AQUI

    // Carrega o Driver apenas uma vez na vida da aplicação
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Erro Crítico: Driver MySQL não encontrado.");
            e.printStackTrace();
        }
    }

    // === 2. FÁBRICA DE CONEXÕES ===
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    // === 3. O VALIDADOR (O Scanner de Segurança) ===
    /**
     * Valida se o dado é seguro e segue o formato esperado.
     *
     * @param data O texto a ser verificado
     * @param type O tipo esperado: "EMAIL", "TEXTO", "SENHA", "NUMERO"
     * @return true se for seguro, false se for suspeito/inválido
     */
    public static boolean isValid(String data, String type) {
        if (data == null || data.trim().isEmpty()) {
            return false; // Dado vazio é inválido
        }

        // 1. Verificação de SQL Injection Básica (Lista Negra)
        // Se alguém tentar injetar comandos SQL, barramos aqui.
        String upperData = data.toUpperCase();
        if (upperData.contains("DROP TABLE")
                || upperData.contains("DELETE FROM")
                || upperData.contains("SELECT *")
                || upperData.contains("--")) {
            System.out.println("⚠️ ALERTA: Tentativa de Injeção detectada: " + data);
            return false;
        }

        // 2. Validações Específicas por Tipo
        switch (type) {
            case "EMAIL":
                // Regex simples para verificar se tem formato de email (@ e .)
                return Pattern.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$", data);

            case "SENHA":
                // Mínimo 4 caracteres (pode aumentar a regra aqui)
                return data.length() >= 4;

            case "NUMERO":
                // Verifica se só tem dígitos
                return data.matches("[0-9]+");

            case "TEXTO":
                // Permite letras, números e espaços, mas bloqueia caracteres muito loucos (< >)
                // Isso ajuda contra XSS (Cross Site Scripting)
                return !data.contains("<") && !data.contains(">");

            default:
                return true;
        }
    }
}
