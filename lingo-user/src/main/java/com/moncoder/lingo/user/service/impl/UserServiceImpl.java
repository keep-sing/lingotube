package com.moncoder.lingo.user.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.extra.mail.MailUtil;
import cn.hutool.extra.qrcode.QrCodeUtil;
import cn.hutool.extra.qrcode.QrConfig;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.moncoder.lingo.common.annotation.RequireLogin;
import com.moncoder.lingo.common.constant.SystemConstant;
import com.moncoder.lingo.common.constant.UserConstant;
import com.moncoder.lingo.common.exception.*;
import com.moncoder.lingo.common.exception.IllegalArgumentException;
import com.moncoder.lingo.common.service.IRedisService;
import com.moncoder.lingo.common.util.RegexUtil;
import com.moncoder.lingo.common.util.UserContext;
import com.moncoder.lingo.entity.UmsUser;
import com.moncoder.lingo.mapper.UmsUserMapper;
import com.moncoder.lingo.user.client.OssClient;
import com.moncoder.lingo.user.config.property.JwtProperties;
import com.moncoder.lingo.user.config.property.WeChatProperties;
import com.moncoder.lingo.user.domain.dto.UserLoginDTO;
import com.moncoder.lingo.user.domain.dto.UserPasswordUpdateDTO;
import com.moncoder.lingo.user.domain.dto.UserRegisterDTO;
import com.moncoder.lingo.user.domain.dto.UserInfoUpdateDTO;
import com.moncoder.lingo.user.domain.vo.UserShowInfoVO;
import com.moncoder.lingo.user.domain.vo.UserInfoVO;
import com.moncoder.lingo.user.domain.vo.WeChatAccessVO;
import com.moncoder.lingo.user.domain.vo.WeChatUserInfoVO;
import com.moncoder.lingo.user.service.IUserService;
import com.moncoder.lingo.user.util.JwtTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author moncoder
 * @since 2024-03-20 16:27:25
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UmsUserMapper, UmsUser> implements IUserService {

    @Autowired
    private IRedisService redisService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private JwtTool jwtTool;
    @Autowired
    private OssClient ossClient;
    @Autowired
    private WeChatProperties wxProperties;
    @Autowired
    private RestTemplate restTemplate;

    /**
     * 发送验证码到邮箱
     *
     * @param email
     * @return
     */
    @Override
    public boolean sendVerifyCode(String email) {
        // 1.参数校验
        if (StrUtil.isBlank(email)) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        // 2.邮箱格式验证
        if (!RegexUtil.isEmail(email)) {
            throw new IllegalArgumentException("邮箱格式不正确！");
        }
        // 3.5min内不重复发送验证码
        Boolean hasCode = redisService.hasKey(UserConstant.UMS_USER_CODE + email);
        if (hasCode) {
            throw new ApiException("验证码已发送！");
        }
        // 4.生成6位验证码
        String code = RandomUtil.randomNumbers(6);
        // 5.发送验证码到指定邮箱
        MailUtil.send(email, UserConstant.UMS_USER_CODE_MAIL_SUBJECT,
                UserConstant.UMS_USER_CODE_MAIL_CONTENT + code, false);
        // 6.将验证码存入redis
        redisService.set(UserConstant.UMS_USER_CODE + email, code, UserConstant.UMS_USER_CODE_EXPIRE);
        return true;
    }

    @Override
    public boolean register(UserRegisterDTO userRegisterDTO) {
        // 1.参数校验
        if (userRegisterDTO == null) {
            throw new NullPointerException("参数不能为null！");
        }
        // 2.邮箱号格式验证
        String email = userRegisterDTO.getEmail();
        if (!RegexUtil.isEmail(email)) {
            throw new IllegalArgumentException("邮箱格式不正确！");
        }
        // 3.判断邮箱是否已经被注册
        List<UmsUser> list = lambdaQuery().eq(UmsUser::getEmail, email).list();
        if (list.size() > 0) {
            throw new ApiException("邮箱已经注册过了！");
        }
        // 4.判断验证码是否正确
        String verifyCode = userRegisterDTO.getVerifyCode();
        log.debug(verifyCode);
        String authCode = (String) redisService.get(UserConstant.UMS_USER_CODE + email);
        if (!verifyCode.equals(authCode)) {
            throw new ApiException("验证码错误！");
        }
        // 5.进行注册
        UmsUser umsUser = new UmsUser();
        BeanUtils.copyProperties(userRegisterDTO, umsUser);
        umsUser.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));
        umsUser.setGender((byte) 0);
        // 6.获取系统注册用户数量
        String key = SystemConstant.LINGO_USER_COUNT;
        // Long会报错
        Integer userCount = (Integer) redisService.get(key);
        redisService.incr(key, 1L);
        umsUser.setNickname(userCount + UserConstant.UMS_USER_USERNAME_SUFFIX);
        return save(umsUser);
    }

    @Override
    public String login(UserLoginDTO userLoginDTO) {
        // 1.参数校验
        String email = userLoginDTO.getEmail();
        String password = userLoginDTO.getPassword();
        if (!RegexUtil.isEmail(email)) {
            throw new IllegalArgumentException("邮箱格式不正确！");
        }
        // 2.验证用户账号状况
        UmsUser user = lambdaQuery().eq(UmsUser::getEmail, email).one();
        // 2.1 账号不存在
        if (user == null) {
            throw new ApiException("请先注册！");
        }
        // 2.2 账号被禁用
        if (user.getStatus().equals((byte) 0)) {
            throw new ForbiddenException("账号被禁用！");
        }
        // 2.3 密码错误
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BadRequestException("密码错误！");
        }
        // 3.返回token
        return jwtTool.createToken(Long.valueOf(user.getId()), jwtProperties.getTokenTTL());
    }

    @RequireLogin
    @Override
    public UserInfoVO getInfo() {
        UmsUser user = lambdaQuery().eq(UmsUser::getId, UserContext.getUser()).one();
        UserInfoVO userInfoVO = new UserInfoVO();
        BeanUtils.copyProperties(user, userInfoVO);
        return userInfoVO;
    }

    @RequireLogin
    @Override
    public boolean updateInfo(UserInfoUpdateDTO userUpdateInfoDTO) {
        // 1.参数验证
        if (userUpdateInfoDTO == null) {
            throw new NullPointerException("参数不能为null！");
        }
        // 2.进行修改
        String nickname = userUpdateInfoDTO.getNickname();
        String introduce = userUpdateInfoDTO.getIntroduce();
        Byte gender = userUpdateInfoDTO.getGender();
        LocalDate birthday = userUpdateInfoDTO.getBirthday();
        String address = userUpdateInfoDTO.getAddress();
        return lambdaUpdate().eq(UmsUser::getId, UserContext.getUser())
                .set(StrUtil.isNotEmpty(nickname), UmsUser::getNickname, nickname)
                .set(StrUtil.isNotEmpty(introduce), UmsUser::getIntroduce, introduce)
                .set(gender != null, UmsUser::getGender, gender)
                .set(birthday != null, UmsUser::getBirthday, birthday)
                .set(StrUtil.isNotEmpty(address), UmsUser::getAddress, address)
                .update();
    }

    @Override
    public boolean verifyCode(String phone, String code) {
        String authCode = (String) redisService.get(UserConstant.UMS_USER_CODE + phone);
        return code.equals(authCode);
    }

    @Override
    public boolean updatePassword(UserPasswordUpdateDTO passwordUpdateDTO) {
        String email = passwordUpdateDTO.getEmail();
        String verifyCode = passwordUpdateDTO.getVerifyCode();
        String newPassword = passwordUpdateDTO.getNewPassword();
        if (!verifyCode(email, verifyCode)) {
            throw new ApiException("验证码错误！");
        }
        return lambdaUpdate().eq(RegexUtil.isEmail(email), UmsUser::getEmail, email)
                .set(StrUtil.isNotBlank(newPassword), UmsUser::getPassword, passwordEncoder.encode(newPassword))
                .set(UmsUser::getUpdatedTime, LocalDateTime.now())
                .update();
    }

    @RequireLogin
    @Override
    public boolean updateAvatar(MultipartFile file) {
        // 1.上传头像并获取uri
        String avatar = ossClient.uploadUserAvatar(file).getData();
        if (avatar == null) {
            throw new FileUploadException("文件上传失败！");
        }
        // 2.设置用户头像uri
        UmsUser umsUser = new UmsUser();
        umsUser.setId(UserContext.getUser());
        umsUser.setAvatar(avatar);
        return updateById(umsUser);
    }

    @RequireLogin
    @Override
    public String getAvatar() {
        return getById(UserContext.getUser()).getAvatar();
    }

    @Override
    public UserShowInfoVO getShowInfo(Integer id) {
        UmsUser user = lambdaQuery().eq(UmsUser::getId, id).one();
        UserShowInfoVO userShowInfoVO = new UserShowInfoVO();
        BeanUtils.copyProperties(user, userShowInfoVO);
        return userShowInfoVO;
    }

    @Override
    public String generateQRCode() throws IOException {
        // 1.设置返回的二维码存储路径
        String qrcodeUrl = "D://qrcode.png";
        // 2.生成二维码
        QrConfig qrConfig = new QrConfig(500, 500);
        qrConfig.setErrorCorrection(ErrorCorrectionLevel.H);

        QrCodeUtil.generate("https://www.tuhu.cn/", qrConfig, FileUtil.file(qrcodeUrl));
        // 3.写入的响应值中
        return qrcodeUrl;
    }


    @Override
    public String wxSignatureCheck(String signature, String timestamp, String nonce, String echostr) {
        // 1.对token、timestamp、nonce进行字典序排序
        String wxToken = "lingo";
        String[] ss = {wxToken, timestamp, nonce};
        Arrays.sort(ss);
        StringBuilder res = new StringBuilder();
        for (String s : ss) {
            res.append(s);
        }
        String sha1 = SecureUtil.sha1(res.toString());
        // 2.验证sha1加密后结果是否与signature相等
        if (sha1.equals(signature)) {
            log.debug(sha1);
            log.debug(signature);
            return echostr;
        }
        return null;
    }

    @Override
    public String getWeChatLoginQRCode() throws UnsupportedEncodingException {
        // 1.设置微信扫码跳转地址
        String appId = wxProperties.getAppId();
        String redirectUri = URLEncoder.encode(wxProperties.getRedirectUri(),
                "UTF-8");
        String url = "https://open.weixin.qq.com/connect/oauth2/authorize?appid=" + appId
                + "&redirect_uri=" + redirectUri
                + "&response_type=code&scope=snsapi_userinfo&state=STATE#wechat_redirect";
        // 2.生成微信授权界面二维码
        QrConfig qrConfig = new QrConfig(500, 500);
        qrConfig.setErrorCorrection(ErrorCorrectionLevel.H);
        String qrcodeUrl = "D://wxQrCode.png";
        QrCodeUtil.generate(url, qrConfig, FileUtil.file(qrcodeUrl));
        return qrcodeUrl;
    }

    @Override
    public WeChatAccessVO weChatCallback(String code) {
        // 1.向微信服务器发送请求，通过code换取access_token
        String accessTokenUrl = "https://api.weixin.qq.com/sns/oauth2/access_token?"
                + "appid=" + wxProperties.getAppId()
                + "&secret=" + wxProperties.getAppSecret()
                + "&code=" + code
                + "&grant_type=authorization_code";
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> response = restTemplate.getForEntity(accessTokenUrl, String.class);
        // 2.解析返回的数据
        WeChatAccessVO wxAccessVO = JSONUtil.toBean(response.getBody(), WeChatAccessVO.class);
        if (wxAccessVO == null) {
            throw new ApiException("微信授权失败！");
        }
        return wxAccessVO;
    }

    @Override
    public WeChatUserInfoVO getWeChatLoginUserInfo(String accessToken, String openId) {
        // 1.向微信服务器发送请求
        String userInfoUrl = "https://api.weixin.qq.com/sns/userinfo?"
                + "access_token=" + accessToken
                + "&openid=" +openId
                + "&lang=zh_CN";
        ResponseEntity<String> response = restTemplate.getForEntity(userInfoUrl, String.class);
        // 2.解析返回的数据
        WeChatUserInfoVO weChatUserInfoVO = JSONUtil.toBean(response.getBody(), WeChatUserInfoVO.class);
        if (weChatUserInfoVO == null){
            throw new ApiException("获取微信用户信息失败！");
        }
        return weChatUserInfoVO;
    }
}
