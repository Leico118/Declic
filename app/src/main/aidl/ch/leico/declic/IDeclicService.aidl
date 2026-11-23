package ch.leico.declic;

import ch.leico.declic.IEventCallback;

interface IDeclicService {
    void destroy() = 16777114; // appelé par Shizuku pour arrêter le moteur
    void exit() = 1;
    String ping() = 2;
    void setTrigger(String deviceName, int type, int code) = 3;
    void setTarget(boolean enabled, float x, float y) = 4;
    void setInjectMode(int mode) = 5;
    void startDetection(IEventCallback cb) = 6;
    void stopDetection() = 7;
}
