import java.nio.file.*;
import org.springframework.mock.web.MockMultipartFile;
import com.td.xp.PreviewController;
class ReportSmoke {
 public static void main(String[] args) throws Exception {
  var result=new PreviewController().exportErrors(new MockMultipartFile("file","供应商结算导入测试.xlsx","",Files.readAllBytes(Path.of(args[0]))));
  Files.write(Path.of(args[1]),result.getBody());
  System.out.println("Report exported: "+result.getBody().length+" bytes");
 }
}
