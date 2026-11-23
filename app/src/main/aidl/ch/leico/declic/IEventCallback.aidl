package ch.leico.declic;

oneway interface IEventCallback {
    void onEvent(String deviceName, int type, int code, int value);
}
