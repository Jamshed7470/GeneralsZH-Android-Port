package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Вход в BOZI — первый экран приложения.
 *
 * <p>Учётная запись нужна не для формальности: игры раздаёт наш сервер, и
 * именно поэтому у всех игроков одна и та же версия сборки. Без общей версии
 * сетевая партия в Generals рассинхронизируется на первой минуте.
 *
 * <p>Пароль на телефоне не сохраняется — только выданный сервером токен,
 * который живёт три месяца.
 */
public class BoziAuthActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText loginField;
    private EditText passwordField;
    private TextView errorView;
    private TextView submit;
    private TextView switchMode;
    private boolean registering;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BoziConfig.signedIn(this)) {
            // Токен есть — проверять его у сервера прямо сейчас не станем:
            // без сети игрок всё равно должен попасть в приложение и увидеть
            // уже скачанные игры. Просроченный токен обнаружится при первом
            // же обращении к каталогу, и тогда экран входа откроется сам.
            openHome();
            return;
        }
        build();
    }

    private void build() {
        LinearLayout root = BoziUi.screen(this, R.drawable.bozi_bg_auth);
        // Знак вместо надписи «BOZI»: экран входа — единственное место, где у
        // приложения есть повод показать себя целиком.
        BoziUi.logo(this, root, R.drawable.bozi_logo);
        BoziUi.label(this, root, "Игровая платформа. Вход по учётной записи — так у всех игроков одна версия игры.", BoziUi.MUTED);

        LinearLayout card = BoziUi.card(this, root);
        loginField = BoziUi.input(this, card, "Логин", false);
        passwordField = BoziUi.input(this, card, "Пароль", true);
        errorView = BoziUi.label(this, card, "", BoziUi.BAD);
        submit = BoziUi.button(this, card, "Войти", true, v -> send());
        switchMode = BoziUi.button(this, card, "Регистрация", false, v -> toggleMode());

        BoziUi.label(this, root,
                "Логин — от 3 до 32 символов: буквы, цифры, точка, дефис, подчёркивание. Пароль — от 6 символов.",
                BoziUi.MUTED);
    }

    private void toggleMode() {
        registering = !registering;
        submit.setText(registering ? "Создать учётную запись" : "Войти");
        switchMode.setText(registering ? "У меня уже есть учётная запись" : "Регистрация");
        errorView.setText("");
    }

    private void send() {
        if (busy) return;
        String login = loginField.getText().toString().trim();
        String password = passwordField.getText().toString();
        if (login.isEmpty() || password.isEmpty()) {
            errorView.setText("Заполните логин и пароль");
            return;
        }
        busy = true;
        errorView.setText("");
        submit.setText("Соединяемся…");
        new Thread(() -> {
            try {
                BoziApi api = new BoziApi(this);
                if (registering) {
                    api.register(login, password);
                } else {
                    api.login(login, password);
                }
                ui.post(this::openHome);
            } catch (Exception e) {
                ui.post(() -> {
                    busy = false;
                    submit.setText(registering ? "Создать учётную запись" : "Войти");
                    errorView.setText(message(e));
                });
            }
        }, "bozi-auth").start();
    }

    static String message(Exception e) {
        String text = e.getMessage();
        return (text == null || text.isEmpty()) ? "не получилось, попробуйте ещё раз" : text;
    }

    private void openHome() {
        startActivity(new Intent(this, BoziHomeActivity.class));
        finish();
    }

    /** Возврат сюда после выхода из учётной записи. */
    static void signOut(Activity from) {
        BoziConfig.forgetSession(from);
        Intent intent = new Intent(from, BoziAuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(intent);
        from.finish();
    }

    @Override
    public void onBackPressed() {
        // С экрана входа назад некуда: это первый экран приложения.
        finishAffinity();
    }

}
