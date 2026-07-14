package com.termux.app.fragments;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxSessionsListAdapter;
import com.termux.shared.shell.TermuxSession;

import java.util.List;

public class TerminalListFragment extends Fragment {

    private TermuxService mTermuxService;
    private ListView mSessionListView;
    private TextView mEmptyView;
    private TermuxSessionsListAdapter mAdapter;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mTermuxService = ((TermuxService.LocalBinder) service).service;
            updateSessionList();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTermuxService = null;
        }
    };

    public TerminalListFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal_list, container, false);

        mSessionListView = view.findViewById(R.id.session_list);
        mEmptyView = view.findViewById(R.id.empty_view);

        Button newTerminalButton = view.findViewById(R.id.new_terminal_button);
        newTerminalButton.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), TermuxActivity.class);
            startActivity(intent);
        });

        mSessionListView.setOnItemClickListener((parent, v, position, id) -> {
            Intent intent = new Intent(getActivity(), TermuxActivity.class);
            startActivity(intent);
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent serviceIntent = new Intent(getContext(), TermuxService.class);
        getContext().startService(serviceIntent);
        getContext().bindService(serviceIntent, mServiceConnection, 0);
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            getContext().unbindService(mServiceConnection);
        } catch (Exception e) {
        }
    }

    private void updateSessionList() {
        if (mTermuxService == null || getActivity() == null) return;

        List<TermuxSession> sessions = mTermuxService.getTermuxSessions();

        if (sessions.isEmpty()) {
            mSessionListView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.VISIBLE);
        } else {
            mSessionListView.setVisibility(View.VISIBLE);
            mEmptyView.setVisibility(View.GONE);

            boolean isBlackUI = false;
            mAdapter = new TermuxSessionsListAdapter(getActivity(), sessions, isBlackUI);
            mSessionListView.setAdapter(mAdapter);
        }
    }
}