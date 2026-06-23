package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    //获取当前线程
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
