package com.app.dto;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

public class DatabaseManager {

    private static final String URL = "jdbc:mysql://localhost:3306/residencia?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8";
    private static final String USER = "Freecs";
    private static final String PASS = "#SQLUser02";


    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("❌ Erro Crítico: Driver MySQL não encontrado.");
            e.printStackTrace();
        }
    }


    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

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

        String upperData = data.toUpperCase();
        if (upperData.contains("DROP TABLE")
                || upperData.contains("DELETE FROM")
                || upperData.contains("SELECT *")
                || upperData.contains("--")) {
            System.out.println("⚠️ ALERTA: Tentativa de Injeção detectada: " + data);
            return false;
        }

        // Validações Específicas por Tipo
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
                return !data.contains("<") && !data.contains(">");

            default:
                return true;
        }
    }

    public static String buscarApiKeyDoBanco() throws Exception {
        String sql = "SELECT config_value FROM system_configs WHERE config_key = 'GEMINI_API_KEY'";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getString("config_value");
            } else {
                throw new Exception("Chave GEMINI_API_KEY não encontrada no banco de dados.");
            }
        }
    }

    public static String retornKeyApi() throws Exception {
        return buscarApiKeyDoBanco();
    }

    public static void salvarItensNota(int transacaoId, String jsonItens) throws Exception {
        if (jsonItens == null || jsonItens.trim().isEmpty() || jsonItens.equals("null")) {
            return;
        }

        com.google.gson.JsonArray array = new com.google.gson.Gson().fromJson(jsonItens, com.google.gson.JsonArray.class);

        try (java.sql.Connection conn = getConnection()) {

            String sqlDelete = "DELETE FROM transaction_items WHERE transaction_id = ?";
            try (java.sql.PreparedStatement psDelete = conn.prepareStatement(sqlDelete)) {
                psDelete.setInt(1, transacaoId);
                psDelete.executeUpdate();
            }

            String sqlInsert = "INSERT INTO transaction_items (transaction_id, name, quantity, price, owner) VALUES (?, ?, ?, ?, ?)";
            try (java.sql.PreparedStatement psInsert = conn.prepareStatement(sqlInsert)) {

                for (int i = 0; i < array.size(); i++) {
                    com.google.gson.JsonObject item = array.get(i).getAsJsonObject();

                    psInsert.setInt(1, transacaoId);
                    psInsert.setString(2, item.get("name").getAsString());
                    psInsert.setDouble(3, item.get("quantity").getAsDouble());
                    psInsert.setDouble(4, item.get("price").getAsDouble());

                    String owner = "HOUSE";
                    if (item.has("owner") && !item.get("owner").isJsonNull()) {
                        String jsonOwner = item.get("owner").getAsString();
                        if ("ME".equals(jsonOwner)) {
                            owner = "USER"; 
                            owner = jsonOwner; 
                        }
                    }
                    psInsert.setString(5, owner);

                    psInsert.addBatch();
                }

                psInsert.executeBatch();
                System.out.println("LOG MATRIX: " + array.size() + " itens salvos (e atualizados) para a transação " + transacaoId);
            }
        }
    }
}
