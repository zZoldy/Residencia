package com.app.dto;

import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class DatabaseManager {

    private static final String URL = "jdbc:mysql://localhost:3306/residencia?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo&useUnicode=true&characterEncoding=UTF-8";
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
                // Mínimo 4 caracteres (Modelo para TESTE)
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

    /**
     * Sincroniza uma lista de itens com a tabela de estoque da casa (Pantry).
     *
     * @param houseId ID da residência
     * @param itens Lista de itens (vindos do WalletServlet)
     * @param loggedUserId ID do usuário que está registrando
     */
    public static void sincronizarDispensa(int houseId, List<ItemNota> itens, int loggedUserId) {
        String sql = "INSERT INTO pantry_items (house_id, product_name, owner_name, quantity) "
                + "VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)";

        // Nomes dos moradores - rótulo da dispensa
        Map<Integer, String> nomesMoradores = new HashMap<>();
        String sqlUsers = "SELECT id, name FROM users WHERE house_id = ?";

        try (Connection conn = getConnection()) {
            // Busca nomes
            try (PreparedStatement stmtUser = conn.prepareStatement(sqlUsers)) {
                stmtUser.setInt(1, houseId);
                ResultSet rs = stmtUser.executeQuery();
                while (rs.next()) {
                    nomesMoradores.put(rs.getInt("id"), rs.getString("name"));
                }
            }

            // Processa o estoque em lote (Batch)
            try (PreparedStatement stmtPantry = conn.prepareStatement(sql)) {
                for (ItemNota item : itens) {
                    String donoFinal = "CASA";

                    if (!"HOUSE".equals(item.owner)) {
                        int donoId = item.owner.startsWith("USER_")
                                ? Integer.parseInt(item.owner.replace("USER_", ""))
                                : loggedUserId;
                        donoFinal = nomesMoradores.getOrDefault(donoId, "MORADOR").toUpperCase();
                    }

                    stmtPantry.setInt(1, houseId);
                    stmtPantry.setString(2, item.name.toUpperCase().trim());
                    stmtPantry.setString(3, donoFinal);
                    stmtPantry.setDouble(4, item.quantity);
                    stmtPantry.addBatch();
                }
                stmtPantry.executeBatch();
                System.out.println("[DB_MANAGER] Sincronia de Dispensa: OK");
            }
        } catch (Exception e) {
            System.err.println("[ERRO CRÍTICO DB] Falha na sincronia de dispensa: " + e.getMessage());
        }
    }
    
    /**
 * Busca todos os itens da dispensa de uma casa.
 */
public static List<JsonObject> buscarItensDispensa(int houseId) {
    List<JsonObject> lista = new ArrayList<>();
    String sql = "SELECT * FROM pantry_items WHERE house_id = ? ORDER BY product_name ASC";

    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        
        stmt.setInt(1, houseId);
        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", rs.getInt("id"));
            item.addProperty("product_name", rs.getString("product_name"));
            item.addProperty("owner_name", rs.getString("owner_name"));
            item.addProperty("quantity", rs.getDouble("quantity"));
            item.addProperty("unit", rs.getString("unit"));
            lista.add(item);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    return lista;
}

/**
 * Reduz em 1 unidade a quantidade de um item.
 */
public static boolean consumirItemDispensa(int itemId) {
    String sql = "UPDATE pantry_items SET quantity = quantity - 1 WHERE id = ? AND quantity > 0";
    try (Connection conn = getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        
        stmt.setInt(1, itemId);
        return stmt.executeUpdate() > 0;
    } catch (Exception e) {
        e.printStackTrace();
        return false;
    }
}
}
