package com.app.residencia;

import com.app.dto.DatabaseManager;
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
                    stripper.setSortByPosition(true);
                    String textoSujoPDF = stripper.getText(document);

                    if (textoSujoPDF.length() < 20) {
                        throw new Exception("O documento PDF está vazio ou não pôde ser lido.");
                    }

                    // --- INÍCIO DA PREPARAÇÃO ---
                    String apiKey = DatabaseManager.retornKeyApi();
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

                    // --- LÓGICA DE RETRY (BLINDAGEM CONTRA ERRO 503) ---
                    String iaResponseStr = "";
                    int tentativas = 0;
                    int maxTentativas = 3;
                    boolean sucessoIA = false;

                    while (tentativas < maxTentativas && !sucessoIA) {
                        try {
                            java.net.URL url = new java.net.URL(endpoint);
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            conn.setDoOutput(true);

                            // Envia o JSON
                            try (java.io.OutputStream os = conn.getOutputStream()) {
                                byte[] input = gson.toJson(payloadIA).getBytes("utf-8");
                                os.write(input, 0, input.length);
                            }

                            int responseCode = conn.getResponseCode();
                            
                            // Se o Google estiver ocupado (503), lançamos erro para cair no Catch e tentar de novo
                            if (responseCode == 503) {
                                throw new Exception("503");
                            }

                            InputStream inputStream = (responseCode >= 200 && responseCode <= 299) 
                                                      ? conn.getInputStream() : conn.getErrorStream();

                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, "utf-8"))) {
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line.trim());
                            }

                            if (responseCode != 200) {
                                throw new Exception("Erro Google " + responseCode + ": " + sb.toString());
                            }

                            iaResponseStr = sb.toString();
                            sucessoIA = true; // Sucesso! Sai do while

                        } catch (Exception e) {
                            if ("503".equals(e.getMessage()) && tentativas < maxTentativas - 1) {
                                tentativas++;
                                System.out.println("⚠️ Google ocupado (503). Tentando novamente (" + tentativas + ")...");
                                Thread.sleep(2000); // Espera 2 segundos
                            } else {
                                throw e; // Outro erro ou fim das tentativas.
                            }
                        }
                    }

                    // --- PROCESSAMENTO DA RESPOSTA DA IA ---
                    JsonObject geminiResult = gson.fromJson(iaResponseStr, JsonObject.class);
                    String textoResposta = geminiResult.getAsJsonArray("candidates")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("content")
                            .getAsJsonArray("parts")
                            .get(0).getAsJsonObject()
                            .get("text").getAsString();

                    textoResposta = textoResposta.replace("```json", "").replace("```", "").trim();
                    JsonArray itensLimpos = gson.fromJson(textoResposta, JsonArray.class);

                    for (int i = 0; i < itensLimpos.size(); i++) {
                        JsonObject p = itensLimpos.get(i).getAsJsonObject();
                        p.addProperty("id", UUID.randomUUID().toString());
                        produtos.add(p);
                    }
                }
            } 
            // ==========================================
            // ROTA 2: PROCESSAMENTO DE HTML (JSOUP)
            // ==========================================
            else {
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
                        } catch (Exception e) {}
                    }
                }
            }

            // Validação e Resposta Final
            if (produtos.size() == 0) {
                throw new Exception("Nenhum produto extraído do documento.");
            }

            JsonObject resposta = new JsonObject();
            resposta.addProperty("status", "success");
            resposta.add("items", produtos);
            response.getWriter().write(gson.toJson(resposta));

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            JsonObject erro = new JsonObject();
            erro.addProperty("status", "error");
            erro.addProperty("message", "Erro: " + e.getMessage());
            response.getWriter().write(gson.toJson(erro));
        }
    }
}