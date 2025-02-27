package org.docksidestage.app.web.signin;

import javax.validation.constraints.NotEmpty;

import org.hibernate.validator.constraints.Length;

/**
 * @author jflute
 * @author inoue
 */
public class SigninForm {

    @NotEmpty
    @Length(max = 50)
    private String account;

    @NotEmpty
    @Length(max = 20)
    private String password;

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
