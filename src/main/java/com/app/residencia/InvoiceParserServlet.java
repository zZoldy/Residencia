package com.app.residencia;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

@WebServlet("/api/leitor-nota")
@MultipartConfig(fileSizeThreshold = 1024 * 1024, maxFileSize = 1024 * 1024 * 5)
public class InvoiceParserServlet extends HttpServlet {

    private static final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            Part filePart = request.getPart("file");
            String fileName = filePart.getSubmittedFileName().toLowerCase();
            InputStream fileContent = filePart.getInputStream();
            JsonArray produtos = new JsonArray();

            // ==========================================
            // ROTA 1: PROCESSAMENTO DE PDF (VIA GEMINI AI)
            // ==========================================
            if (fileName.endsWith(".pdf")) {
                try (PDDocument document = PDDocument.load(fileContent)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true); // Mantemos a ordem visual
                    String textoSujoPDF = stripper.getText(document);

                    if (textoSujoPDF.length() < 20) {
                        throw new Exception("O documento PDF está vazio ou não pôde ser lido.");
                    }

                    // --- INÍCIO DA CONEXÃO COM A IA ---
                    String apiKey = "AIzaSyAdPF2SliZ6SraBO3PN6wb2i09grLOox_U"; // Sua chave
                    String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=" + apiKey;
                    
                    String prompt = "Você é um extrator de cupons fiscais. Vou te passar o texto sujo de um PDF de supermercado. "
                            + "Sua missão é IGNORAR cabeçalhos, CNPJ, palavras como 'DOCUMENTO AUXILIAR', endereços (QUADRA, S/N, LOTE), formas de pagamento, troco e códigos numéricos soltos. "
                            + "Retorne ESTRITAMENTE um array JSON válido, SEM marcações markdown (não use ```json), contendo APENAS os produtos de mercado válidos. "
                            + "Formato exigido: [{\"name\": \"NOME LIMPO DO PRODUTO\", \"quantity\": 1.0, \"price\": 10.50}]. "
                            + "Se houver itens repetidos, some as quantidades e o 'price' total. "
                            + "O campo 'price' é sempre o valor TOTAL do item (quantidade * valor unitário). "
                            + "Texto da nota: \n\n" + textoSujoPDF;

                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("text", prompt);
                    JsonArray partsArray = new JsonArray();
                    partsArray.add(textPart);
                    JsonObject contentObj = new JsonObject();
                    contentObj.add("parts", partsArray);
                    JsonArray contentsArray = new JsonArray();
                    contentsArray.add(contentObj);
                    JsonObject payloadIA = new JsonObject();
                    payloadIA.add("contents", contentsArray);

                    // Dispara para a nuvem
                    java.net.URL url = new java.net.URL(endpoint);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);

                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        byte[] input = gson.toJson(payloadIA).getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }

                    // === O SEGREDO ESTÁ AQUI: TRATAMENTO BLINDADO DE RESPOSTA HTTP ===
                    int responseCode = conn.getResponseCode();
                    InputStream inputStream;

                    // Se a API retornar sucesso (200 OK), lemos o InputStream. Se der erro (400, 403, 500), lemos o ErrorStream.
                    if (responseCode >= 200 && responseCode <= 299) {
                        inputStream = conn.getInputStream();
                    } else {
                        inputStream = conn.getErrorStream();
                    }

                    StringBuilder iaResponse = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "utf-8"))) {
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            iaResponse.append(responseLine.trim());
                        }
                    }

                    // Agora verificamos o código. Se explodiu, nós atiramos o erro exato do Google para a tela!
                    if (responseCode != 200) {
                        throw new Exception("O Google recusou a conexão (Erro " + responseCode + "): " + iaResponse.toString());
                    }

                    // Desempacota a resposta do Google
                    JsonObject geminiResult = gson.fromJson(iaResponse.toString(), JsonObject.class);

                    // Trava de segurança: Verifica se a IA devolveu o formato esperado
                    if (!geminiResult.has("candidates")) {
                        throw new Exception("A IA bloqueou a resposta ou retornou dados vazios. Resposta crua: " + iaResponse.toString());
                    }

                    String textoResposta = geminiResult.getAsJsonArray("candidates")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("content")
                            .getAsJsonArray("parts")
                            .get(0).getAsJsonObject()
                            .get("text").getAsString();

                    // Limpa formatação Markdown
                    textoResposta = textoResposta.replace("```json", "").replace("```", "").trim();

                    // Converte de volta para Java
                    JsonArray itensLimpos = gson.fromJson(textoResposta, JsonArray.class);

                    for (int i = 0; i < itensLimpos.size(); i++) {
                        JsonObject p = itensLimpos.get(i).getAsJsonObject();
                        p.addProperty("id", UUID.randomUUID().toString());
                        produtos.add(p);
                    }
                    // --- FIM DA CONEXÃO COM A IA ---
                }
            } // ==========================================
            // ROTA 2: PROCESSAMENTO DE HTML
            // ==========================================
            else {
                // ... (seu código HTML intacto omitido aqui para focar na leitura, mas ele está garantido se você colar tudo)
                StringBuilder textBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(fileContent, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        textBuilder.append(line);
                    }
                }

                Document doc = Jsoup.parse(textBuilder.toString());
                Elements linhasSP = doc.select("tr[id^=Item]");

                if (!linhasSP.isEmpty()) {
                    for (Element linha : linhasSP) {
                        try {
                            String nome = linha.select(".txtTit").text();
                            String qtdStr = linha.select(".Rqtd").text().replaceAll("[^0-9,]", "").replace(",", ".");
                            String precoStr = linha.select(".valor").text().replaceAll("[^0-9,]", "").replace(",", ".");
                            if (!nome.isEmpty() && !precoStr.isEmpty()) {
                                JsonObject p = new JsonObject();
                                p.addProperty("id", UUID.randomUUID().toString());
                                p.addProperty("name", nome);
                                p.addProperty("quantity", qtdStr.isEmpty() ? 1.0 : Double.parseDouble(qtdStr));
                                p.addProperty("price", Double.parseDouble(precoStr));
                                produtos.add(p);
                            }
                        } catch (Exception e) {
                        }
                    }
                }
            }

            // Validação Final
            if (produtos.size() == 0) {
                JsonObject erro = new JsonObject();
                erro.addProperty("status", "error");
                erro.addProperty("message", "Nenhum produto extraído. Verifique o formato do documento.");
                response.getWriter().write(gson.toJson(erro));
                return;
            }

            JsonObject resposta = new JsonObject();
            resposta.addProperty("status", "success");
            resposta.add("items", produtos);
            response.getWriter().write(gson.toJson(resposta));

        } catch (Exception e) {
            // === LOGS DEFINITIVOS AQUI ===
            e.printStackTrace(); // Isto vai imprimir o rastro de sangue vermelho no seu NetBeans

            response.setStatus(500);
            JsonObject erro = new JsonObject();
            erro.addProperty("status", "error");
            // Adicionamos o 'e.getMessage()' para exibir o erro exato no alert() do JavaScript!
            erro.addProperty("message", "Erro no processamento: " + e.getMessage());
            response.getWriter().write(gson.toJson(erro));
        }
    }
}
