package com.termux.app.x11;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** XServer 连接的持久化管理（与 {@link com.termux.app.vnc.VncConnectionManager} 同一套路）。 */
public class XServerConnectionManager {
    private static final String PREFS_NAME = "xserver_connections";
    private static final String KEY_CONNECTIONS = "connections";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public XServerConnectionManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<XServerConnection> getConnections() {
        String json = prefs.getString(KEY_CONNECTIONS, "[]");
        Type type = new TypeToken<ArrayList<XServerConnection>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public void saveConnection(XServerConnection connection) {
        List<XServerConnection> connections = getConnections();
        int index = -1;
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).getId().equals(connection.getId())) {
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
        List<XServerConnection> connections = getConnections();
        connections.removeIf(c -> c.getId().equals(id));
        saveAll(connections);
    }

    private void saveAll(List<XServerConnection> connections) {
        String json = gson.toJson(connections);
        prefs.edit().putString(KEY_CONNECTIONS, json).apply();
    }
}
