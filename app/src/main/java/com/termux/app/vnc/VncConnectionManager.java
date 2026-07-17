package com.termux.app.vnc;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class VncConnectionManager {
    private static final String PREFS_NAME = "vnc_connections";
    private static final String KEY_CONNECTIONS = "connections";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public VncConnectionManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<VncConnection> getConnections() {
        String json = prefs.getString(KEY_CONNECTIONS, "[]");
        Type type = new TypeToken<ArrayList<VncConnection>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public void saveConnection(VncConnection connection) {
        List<VncConnection> connections = getConnections();
        int index = -1;
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).id.equals(connection.id)) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            connections.set(index, connection);
        } else {
            connections.add(connection);
        }
        saveAll(connections);
    }

    public void deleteConnection(String id) {
        List<VncConnection> connections = getConnections();
        connections.removeIf(c -> c.id.equals(id));
        saveAll(connections);
    }

    public void deleteTermuxConnections() {
        List<VncConnection> connections = getConnections();
        connections.removeIf(c -> c.isFromTermux);
        saveAll(connections);
    }

    private void saveAll(List<VncConnection> connections) {
        String json = gson.toJson(connections);
        prefs.edit().putString(KEY_CONNECTIONS, json).apply();
    }
}