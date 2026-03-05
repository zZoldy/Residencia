package com.app.residencia;

import com.app.dto.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

@WebServlet(name = "PantryServlet", urlPatterns = {"/api/pantry", "/api/pantry/consume"})
public class PantryServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();
        String houseId = request.getParameter("houseId");

        if (houseId != null) {
            // Chamamos o método que vamos criar no DatabaseManager
            List<JsonObject> itens = DatabaseManager.buscarItensDispensa(Integer.parseInt(houseId));
            out.print(new Gson().toJson(itens));
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();
        Gson gson = new Gson();

        // Lógica para consumir item (Baixa de Estoque)
        try {
            JsonObject payload = gson.fromJson(request.getReader(), JsonObject.class);
            int itemId = payload.get("id").getAsInt();

            boolean sucesso = DatabaseManager.consumirItemDispensa(itemId);
            
            JsonObject res = new JsonObject();
            res.addProperty("success", sucesso);
            out.print(gson.toJson(res));
        } catch (Exception e) {
            response.setStatus(500);
            out.print("{\"success\": false}");
        }
    }
}