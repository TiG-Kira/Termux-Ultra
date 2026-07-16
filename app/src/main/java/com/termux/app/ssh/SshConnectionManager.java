package com.termux.app.ssh;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class SshConnectionManager {
    private static final String PREFS_NAME = "ssh_connections";
    private static final String KEY_CONNECTIONS = "connections";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public SshConnectionManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<SshConnection> getConnections() {
        String json = prefs.getString(KEY_CONNECTIONS, "[]");
        Type type = new TypeToken<ArrayList<SshConnection>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public void saveConnection(SshConnection connection) {
        List<SshConnection> connections = getConnections();
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
        List<SshConnection> connections = getConnections();
        connections.removeIf(c -> c.id.equals(id));
        saveAll(connections);
    }

    private void saveAll(List<SshConnection> connections) {
        String json = gson.toJson(connections);
        prefs.edit().putString(KEY_CONNECTIONS, json).apply();
    }
}
