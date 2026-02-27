package com.qwerlty.myojbackendcommentservice.controller.inner;


import com.qwerlty.myojbackendserviceclient.client.CommentFeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 黄昊
 * @version 1.0
 **/
@RestController
@RequestMapping("/inner")
public class CommentInnerController implements CommentFeignClient {
}
